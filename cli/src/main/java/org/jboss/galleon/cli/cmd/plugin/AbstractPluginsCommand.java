/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.galleon.cli.cmd.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aesh.command.impl.internal.OptionType;
import org.aesh.command.impl.internal.ProcessedOption;
import org.aesh.command.impl.internal.ProcessedOptionBuilder;
import org.aesh.command.parser.OptionParserException;
import org.aesh.readline.AeshContext;
import org.aesh.utils.Config;
import org.jboss.galleon.DefaultMessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.cli.CommandExecutionException;
import org.jboss.galleon.cli.PmCommandInvocation;
import org.jboss.galleon.cli.PmSession;
import static org.jboss.galleon.cli.AbstractFeaturePackCommand.VERBOSE_OPTION_NAME;
import org.jboss.galleon.cli.cmd.AbstractDynamicCommand;
import org.jboss.galleon.cli.cmd.FPLocationCompleter;
import org.jboss.galleon.cli.model.state.State;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.plugin.PluginOption;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;

/**
 * An abstract command that discover plugin options based on the fp or stream
 * argument.
 *
 * @author jdenise@redhat.com
 */
public abstract class AbstractPluginsCommand extends AbstractDynamicCommand {

    private AeshContext ctx;

    public AbstractPluginsCommand(PmSession pmSession) {
        super(pmSession, true, true);
    }

    public void setAeshContext(AeshContext ctx) {
        this.ctx = ctx;
    }

    protected boolean isVerbose() {
        return contains(VERBOSE_OPTION_NAME);
    }

    @Override
    protected void runCommand(PmCommandInvocation session, Map<String, String> options) throws CommandExecutionException {
        if (isVerbose()) {
            session.getPmSession().enableMavenTrace(true);
        }
        try {
            FeaturePackLocation loc = pmSession.getResolvedLocation(getId(pmSession));
            runCommand(session, options, loc);
        } catch (ProvisioningException ex) {
            throw new CommandExecutionException(ex.getLocalizedMessage(), ex);
        } finally {
            session.getPmSession().enableMavenTrace(false);
        }
    }

    protected abstract void runCommand(PmCommandInvocation session, Map<String, String> options,
            FeaturePackLocation loc) throws CommandExecutionException;

    @Override
    protected void doValidateOptions() throws CommandExecutionException {
        // side effect is to resolve artifact.
        String fpl = getId(pmSession);
        if (fpl == null) {
            throw new CommandExecutionException("Missing feature-pack");
        }
    }

    @Override
    protected List<ProcessedOption> getStaticOptions() throws OptionParserException {
        List<ProcessedOption> options = new ArrayList<>();
        options.add(ProcessedOptionBuilder.builder().name(ARGUMENT_NAME).
                hasValue(true).
                description("FP Location").
                type(String.class).
                required(true).
                optionType(OptionType.ARGUMENT).
                completer(FPLocationCompleter.class).
                build());
        options.add(ProcessedOptionBuilder.builder().name(VERBOSE_OPTION_NAME).
                hasValue(false).
                type(Boolean.class).
                description("Whether or not the output should be verbose").
                optionType(OptionType.BOOLEAN).
                build());
        options.addAll(getOtherOptions());
        return options;
    }

    protected List<ProcessedOption> getOtherOptions() throws OptionParserException {
        return Collections.emptyList();
    }

    @Override
    protected List<DynamicOption> getDynamicOptions(State state, String id) throws Exception {
        List<DynamicOption> options = new ArrayList<>();
        ProvisioningManager manager = getManager(ctx);
        FeaturePackLocation fpl = pmSession.getResolvedLocation(id);
        checkLocalArtifact(fpl.getFPID());
        FeaturePackConfig config = FeaturePackConfig.forLocation(fpl);
        ProvisioningConfig provisioning = ProvisioningConfig.builder().addFeaturePackDep(config).build();
        ProvisioningRuntime runtime = manager.getRuntime(provisioning, null, Collections.emptyMap());
        Set<PluginOption> pluginOptions = getPluginOptions(runtime);
        for (PluginOption opt : pluginOptions) {
            DynamicOption dynOption = new DynamicOption(opt.getName(), opt.isRequired(), opt.isAcceptsValue());
            options.add(dynOption);
        }
        return options;
    }

    protected abstract Set<PluginOption> getPluginOptions(ProvisioningRuntime runtime) throws ProvisioningException;

    protected abstract Path getInstallationHome(AeshContext ctx);

    private ProvisioningManager getManager(AeshContext ctx) throws ProvisioningException {
        ProvisioningManager manager = ProvisioningManager.builder()
                .setInstallationHome(getInstallationHome(ctx))
                .build();
        return manager;
    }

    protected ProvisioningManager getManager(PmCommandInvocation session) throws ProvisioningException {
        return ProvisioningManager.builder()
                .setUniverseResolver(session.getPmSession().getUniverse().getUniverseResolver())
                .setInstallationHome(getInstallationHome(session.getAeshContext()))
                .setMessageWriter(new DefaultMessageWriter(session.getOut(), session.getErr(), isVerbose()))
                .build();
    }

    @Override
    protected String getId(PmSession session) throws CommandExecutionException {
        String streamName = (String) getValue(ARGUMENT_NAME);
        if (streamName == null) {
            // Check in argument or option, that is the option completion case.
            streamName = getArgumentValue();
        }
        return streamName;
    }

    private void checkLocalArtifact(FPID fpid) throws CommandExecutionException, ProvisioningException {
        if (!pmSession.existsInLocalRepository(fpid)) {
            try {
                pmSession.println(Config.getLineSeparator() + "retrieving feature-pack content from remote repository...");
                pmSession.downloadFp(fpid);
            } catch (ProvisioningException ex) {
                throw new CommandExecutionException(ex);
            }
        }
    }
}
