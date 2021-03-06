/**
 * Copyright 2015-2016 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.container.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.LogManager;

import javax.xml.namespace.QName;

import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.server.SelfContainedContainer;
import org.jboss.as.server.Services;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ValueExpression;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.Resource;
import org.jboss.modules.filter.PathFilters;
import org.jboss.msc.service.ServiceActivator;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.ValueService;
import org.jboss.msc.value.ImmediateValue;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.vfs.TempFileProvider;
import org.wildfly.swarm.Swarm;
import org.wildfly.swarm.bootstrap.logging.BootstrapLogger;
import org.wildfly.swarm.bootstrap.util.TempFileManager;
import org.wildfly.swarm.container.Container;
import org.wildfly.swarm.container.Interface;
import org.wildfly.swarm.container.internal.Deployer;
import org.wildfly.swarm.container.internal.Server;
import org.wildfly.swarm.container.runtime.internal.AnnotationBasedServerConfiguration;
import org.wildfly.swarm.spi.api.Fraction;
import org.wildfly.swarm.spi.api.OutboundSocketBinding;
import org.wildfly.swarm.spi.api.SocketBinding;
import org.wildfly.swarm.spi.api.SocketBindingGroup;
import org.wildfly.swarm.spi.api.SwarmProperties;
import org.wildfly.swarm.spi.api.annotations.Configuration;
import org.wildfly.swarm.spi.api.annotations.Default;
import org.wildfly.swarm.spi.runtime.AbstractParserFactory;
import org.wildfly.swarm.spi.runtime.ServerConfiguration;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEFAULT_INTERFACE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INET_ADDRESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MULTICAST_ADDRESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MULTICAST_PORT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PORT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PORT_OFFSET;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUSPEND;

/**
 * @author Bob McWhirter
 * @author Ken Finnigan
 */
@SuppressWarnings("unused")
public class RuntimeServer implements Server {

    @SuppressWarnings("unused")
    public RuntimeServer() {
        try {
            Module loggingModule = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.logging", "runtime"));

            ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loggingModule.getClassLoader());
                System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
                System.setProperty("org.jboss.logmanager.configurator", LoggingConfigurator.class.getName());
                //force logging init
                LogManager.getLogManager();
                BootstrapLogger.setBackingLoggerManager(new JBossLoggingManager());
                //System.clearProperty( "org.jboss.logmanager.configurator" );
            } finally {
                Thread.currentThread().setContextClassLoader(originalCl);
            }
        } catch (ModuleLoadException e) {
            System.err.println("[WARN] logging not available, logging will not be configured");
        }
    }

    @Override
    public void setXmlConfig(URL xmlConfig) {
        if (null == xmlConfig)
            throw new IllegalArgumentException("Invalid XML config");
        this.xmlConfig = Optional.of(xmlConfig);
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    @Override
    public Deployer start(Container config, boolean eagerlyOpen) throws Exception {

        UUID uuid = UUIDFactory.getUUID();
        System.setProperty("jboss.server.management.uuid", uuid.toString());

        loadFractionConfigurations();

        applyDefaults(config);

        for (Fraction fraction : config.fractions()) {
            fraction.postInitialize(config.createPostInitContext());
        }

        if (!xmlConfig.isPresent())
            applySocketBindingGroupDefaults(config);

        LinkedList<ModelNode> bootstrapOperations = new LinkedList<>();

        // the extensions
        getExtensions(config, bootstrapOperations);

        // the subsystem configurations
        getList(config, bootstrapOperations);

        if (LOG.isDebugEnabled()) {
            LOG.debug(bootstrapOperations);
        }

        Thread.currentThread().setContextClassLoader(RuntimeServer.class.getClassLoader());

        UUID grist = java.util.UUID.randomUUID();
        String tmpDir = System.getProperty("java.io.tmpdir");

        File serverTmp = TempFileManager.INSTANCE.newTempDirectory("wildfly-swarm", ".d");
        System.setProperty("jboss.server.temp.dir", serverTmp.getAbsolutePath());

        ScheduledExecutorService tempFileExecutor = Executors.newSingleThreadScheduledExecutor();
        TempFileProvider tempFileProvider = TempFileProvider.create("wildfly-swarm", tempFileExecutor, true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                tempFileProvider.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
        List<ServiceActivator> activators = new ArrayList<>();
        activators.add(context -> {
            context.getServiceTarget().addService(ServiceName.of("wildfly", "swarm", "temp-provider"), new ValueService<>(new ImmediateValue<>(tempFileProvider)))
                    .install();
            // Provide the main command line args as a value service
            context.getServiceTarget().addService(ServiceName.of("wildfly", "swarm", "main-args"), new ValueService<>(new ImmediateValue<>(config.getArgs())))
                    .install();
        });

        for (ServerConfiguration<Fraction> eachConfig : this.configList) {
            boolean found = false;
            for (Fraction eachFraction : config.fractions()) {
                if (eachConfig.getType().isAssignableFrom(eachFraction.getClass())) {
                    found = true;
                    activators.addAll(eachConfig.getServiceActivators(eachFraction));
                    break;
                }
            }
            if (!found && !eachConfig.isIgnorable()) {
                System.err.println("*** unable to find fraction for: " + eachConfig.getType());
            }
        }

        this.serviceContainer = this.container.start(bootstrapOperations, this.contentProvider, activators);
        for (ServiceName serviceName : this.serviceContainer.getServiceNames()) {
            ServiceController<?> serviceController = this.serviceContainer.getService(serviceName);
            StartException exception = serviceController.getStartException();
            if (exception != null) {
                throw exception;
            }
        }

        Opener opener = tryToAddGateHandlers();

        ModelController controller = (ModelController) this.serviceContainer.getService(Services.JBOSS_SERVER_CONTROLLER).getValue();
        Executor executor = Executors.newSingleThreadExecutor();

        if ( eagerlyOpen ) {
            opener.open();
        }

        this.client = controller.createClient(executor);
        this.deployer = new RuntimeDeployer(opener, this.serviceContainer, this.configList, this.client, this.contentProvider, tempFileProvider);
        this.deployer.debug(this.debug);

        this.serviceContainer.addService( ServiceName.of( "swarm", "deployer"), new ValueService<>( new ImmediateValue<Object>( this.deployer ))).install();

        List<Archive> implicitDeployments = new ArrayList<>();

        for (ServerConfiguration<Fraction> eachConfig : this.configList) {
            for (Fraction eachFraction : config.fractions()) {
                if (eachConfig.getType().isAssignableFrom(eachFraction.getClass())) {
                    implicitDeployments.addAll(eachConfig.getImplicitDeployments(eachFraction, Swarm.artifactManager()));
                    break;
                }
            }
        }

        for (Archive each : implicitDeployments) {
            this.deployer.deploy(each);
        }

        return this.deployer;
    }

    protected Opener tryToAddGateHandlers() throws Exception {
        try {
            Module undertowRuntime = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.undertow", "runtime"));

            Class<?> wrapperClass = undertowRuntime.getClassLoader().loadClass("org.wildfly.swarm.undertow.runtime.GateHandlerWrapper");

            Object wrapperInstance = wrapperClass.newInstance();

            ServiceName listenerRoot = ServiceName.of("jboss", "undertow", "listener");
            List<ServiceName> names = this.serviceContainer.getServiceNames();

            for (ServiceName name : names) {
                if (listenerRoot.isParentOf(name)) {
                    ServiceController<?> service = this.serviceContainer.getService(name);

                    Object value = service.getValue();
                    Class<?> cls = value.getClass();

                    OUTER:
                    while (cls != null) {
                        Method[] methods = cls.getDeclaredMethods();
                        INNER:
                        for (int i = 0; i < methods.length; ++i) {
                            Method method = methods[i];
                            if (method.getName().equals("addWrapperHandler")) {
                                method.setAccessible(true);
                                method.invoke(value, wrapperInstance );
                                break OUTER;
                            }
                        }
                        cls = cls.getSuperclass();
                    }
                    service.setMode(ServiceController.Mode.ACTIVE);
                }
            }

            return (Opener) wrapperInstance;
        } catch (ModuleLoadException e) {
            // that's okay, no undertow, quietly return;
        }

        return null;
    }

    public void stop() throws Exception {

        final CountDownLatch latch = new CountDownLatch(1);
        this.serviceContainer.addTerminateListener(info -> latch.countDown());
        this.serviceContainer.shutdown();

        latch.await();

        this.deployer.stop();
        this.serviceContainer = null;
        this.client = null;
        this.deployer = null;
    }

    @Override
    public Set<Class<? extends Fraction>> getFractionTypes() {
        return this.configByFractionType.keySet();
    }

    @Override
    public Fraction createDefaultFor(Class<? extends Fraction> fractionClazz) {
        return this.configByFractionType.get(fractionClazz).defaultFraction();
    }

    private void applyDefaults(Container config) throws Exception {
        config.applyFractionDefaults(this);
        if (!xmlConfig.isPresent()) {
            applyInterfaceDefaults(config);
        }
    }

    private void applyInterfaceDefaults(Container config) {
        if (config.ifaces().isEmpty()) {
            config.iface("public",
                    SwarmProperties.propertyVar(SwarmProperties.BIND_ADDRESS, "0.0.0.0"));
        }
    }

    private void applySocketBindingGroupDefaults(Container config) {
        if (config.socketBindingGroups().isEmpty()) {
            config.socketBindingGroup(
                    new SocketBindingGroup("default-sockets", "public",
                            SwarmProperties.propertyVar(SwarmProperties.PORT_OFFSET, "0"))
            );
        }

        Set<String> groupNames = config.socketBindings().keySet();

        for (String each : groupNames) {
            List<SocketBinding> bindings = config.socketBindings().get(each);

            SocketBindingGroup group = config.getSocketBindingGroup(each);
            if (group == null) {
                throw new RuntimeException("No socket-binding-group for '" + each + "'");
            }

            for (SocketBinding binding : bindings) {
                group.socketBinding(binding);
            }
        }

        groupNames = config.outboundSocketBindings().keySet();

        for (String each : groupNames) {
            List<OutboundSocketBinding> bindings = config.outboundSocketBindings().get(each);

            SocketBindingGroup group = config.getSocketBindingGroup(each);
            if (group == null) {
                throw new RuntimeException("No socket-binding-group for '" + each + "'");
            }

            for (OutboundSocketBinding binding : bindings) {
                group.outboundSocketBinding(binding);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFractionConfigurations() throws Exception {
        Module m1 = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("swarm.application"));

        List<Index> indexes = new ArrayList<>();

        // required for composite index
        resolveBuildTimeIndex(Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.container", "runtime")), indexes);
        resolveBuildTimeIndex(Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.spi", "runtime")), indexes);
        resolveBuildTimeIndex(Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.spi", "main")), indexes);

        Enumeration<URL> bootstraps = m1.getClassLoader().getResources("wildfly-swarm-bootstrap.conf");
        if (!bootstraps.hasMoreElements()) {
            bootstraps = ClassLoader.getSystemClassLoader().getResources("wildfly-swarm-bootstrap.conf");
        }

        while (bootstraps.hasMoreElements()) {
            URL each = bootstraps.nextElement();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(each.openStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    Module module = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create(line, "runtime"));

                    List<Class<? extends ServerConfiguration>> serverConfigs = findServerConfigurationImpls(module, indexes);

                    for (Class<? extends ServerConfiguration> cls : serverConfigs) {
                        if (!this.configList.stream().anyMatch((e) -> e.getClass().equals(cls))) {
                            ServerConfiguration serverConfig = (ServerConfiguration) cls.newInstance();
                            this.configByFractionType.put(serverConfig.getType(), serverConfig);
                            this.configList.add(serverConfig);
                        }
                    }

                    module = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create(line, "api"));
                    List<ServerConfiguration> serverConfigInstances = findAnnotationServerConfigurations(module, indexes);

                    for (ServerConfiguration serverConfigInstance : serverConfigInstances) {
                        if (!this.configList.stream().anyMatch((e) -> e.getType().equals(serverConfigInstance.getType()))) {
                            this.configByFractionType.put(serverConfigInstance.getType(), serverConfigInstance);
                            this.configList.add(serverConfigInstance);
                        }
                    }
                }
            }
        }
    }

    protected List<Class<? extends ServerConfiguration>> findServerConfigurationImpls(Module module, List<Index> parentIndexes) throws ModuleLoadException, IOException, NoSuchFieldException, IllegalAccessException {

        List<Index> indexes = new ArrayList<>();

        resolveBuildTimeIndex(module, indexes);

        //resolveRuntimeIndex(module, indexes);

        indexes.addAll(parentIndexes);
        CompositeIndex compositeIndex = CompositeIndex.create(indexes.toArray(new Index[indexes.size()]));

        List<Class<? extends ServerConfiguration>> impls = new ArrayList<>();

        Set<ClassInfo> infos = compositeIndex.getAllKnownImplementors(DotName.createSimple(ServerConfiguration.class.getName()));

        for (ClassInfo info : infos) {
            if (info.name().toString().equals(AnnotationBasedServerConfiguration.class.getName())) {
                continue;
            }
            try {
                Class<? extends ServerConfiguration> cls = (Class<? extends ServerConfiguration>) module.getClassLoader().loadClass(info.name().toString());

                if (!Modifier.isAbstract(cls.getModifiers())) {
                    impls.add(cls);
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }

        }

        return impls;
    }

    protected List<ServerConfiguration> findAnnotationServerConfigurations(Module apiModule, List<Index> parentIndexes) throws ModuleLoadException, IOException, NoSuchFieldException, IllegalAccessException {

        List<Index> indexes = new ArrayList<>();

        resolveBuildTimeIndex(apiModule, indexes);

        //resolveRuntimeIndex(module, indexes);

        indexes.addAll(parentIndexes);
        CompositeIndex compositeIndex = CompositeIndex.create(indexes.toArray(new Index[indexes.size()]));

        List<ServerConfiguration> impls = new ArrayList<>();

        DotName configAnno = DotName.createSimple(Configuration.class.getName());

        Set<ClassInfo> infos = compositeIndex.getAllKnownImplementors(DotName.createSimple(Fraction.class.getName()));

        for (ClassInfo info : infos) {

            for (AnnotationInstance anno : info.classAnnotations()) {
                if (anno.name().equals(configAnno)) {
                    try {
                        ServerConfiguration config = fromAnnotation(apiModule, anno);
                        impls.add(config);
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }

        }

        return impls;
    }

    protected ServerConfiguration fromAnnotation(Module apiModule, AnnotationInstance anno) throws ClassNotFoundException, ModuleLoadException {
        AnnotationValue marshalValue = anno.value("marshal");
        AnnotationValue ignorableValue = anno.value("ignorable");
        AnnotationValue extensionValue = anno.value("extension");
        AnnotationValue parserFactoryClassNameValue = anno.value("parserFactoryClassName");

        boolean marshal = (marshalValue != null) ? marshalValue.asBoolean() : false;
        boolean ignorable = (ignorableValue != null) ? ignorableValue.asBoolean() : false;
        String extension = (extensionValue != null) ? extensionValue.asString() : null;
        String parserFactoryClass = (parserFactoryClassNameValue != null) ? parserFactoryClassNameValue.asString() : null;

        Module mainModule = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create(apiModule.getIdentifier().getName(), "main"));

        Class<? extends Fraction> fractionClass = (Class<? extends Fraction>) mainModule.getClassLoader().loadClass(anno.target().asClass().name().toString());

        AnnotationBasedServerConfiguration serverConfig = new AnnotationBasedServerConfiguration(fractionClass);

        serverConfig.ignorable(ignorable);
        serverConfig.extension(extension);
        serverConfig.marshal(marshal);

        if (parserFactoryClass != null && !parserFactoryClass.equals("")) {
            Module runtimeModule = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create(apiModule.getIdentifier().getName(), "runtime"));
            Class<? extends AbstractParserFactory> parserFractoryClass = (Class<? extends AbstractParserFactory>) runtimeModule.getClassLoader().loadClass(parserFactoryClass);

            serverConfig.parserFactoryClass(parserFractoryClass);
        }

        List<MethodInfo> fractionMethods = anno.target().asClass().methods();

        DotName defaultAnno = DotName.createSimple(Default.class.getName());

        boolean foundDefault = false;

        for (MethodInfo each : fractionMethods) {
            if (each.hasAnnotation(defaultAnno)) {
                if (!each.parameters().isEmpty()) {
                    throw new RuntimeException("Method marked @Default must require zero parameters");
                }

                if (!Modifier.isStatic(each.flags())) {
                    throw new RuntimeException("Method marked @Default must be static");
                }

                if (foundDefault) {
                    throw new RuntimeException("Multiple methods found marked as @Default");
                }

                foundDefault = true;

                serverConfig.defaultFraction(each.name());
            }
        }


        return serverConfig;
    }

    private void resolveBuildTimeIndex(Module module, List<Index> indexes) {
        try {
            Enumeration<URL> indexFiles = module.getClassLoader().findResources(BUILD_TIME_INDEX_NAME, false);

            while (indexFiles.hasMoreElements()) {
                URL next = indexFiles.nextElement();
                //System.out.println("Found : "+ next);
                InputStream input = next.openStream();
                IndexReader reader = new IndexReader(input);
                Index index = reader.read();
                try {
                    indexes.add(index);
                } finally {
                    input.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void resolveRuntimeIndex(Module module, List<Index> indexes) throws ModuleLoadException {
        Indexer indexer = new Indexer();

        Iterator<Resource> resources = module.iterateResources(PathFilters.acceptAll());

        while (resources.hasNext()) {
            Resource each = resources.next();

            if (each.getName().endsWith(".class")) {
                try {
                    ClassInfo clsInfo = indexer.index(each.openStream());
                } catch (IOException e) {
                    //System.err.println("error: " + each.getName() + ": " + e.getMessage());
                }
            }
        }

        indexes.add(indexer.complete());
    }

    private void getExtensions(Container container, List<ModelNode> list) throws Exception {

        FractionProcessor<List<ModelNode>> consumer = (context, cfg, fraction) -> {
            try {
                Optional<ModelNode> extension = cfg.getExtension();
                extension.map(modelNode -> list.add(modelNode));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        visitFractions(container, list, consumer);

    }

    private void getList(Container config, List<ModelNode> list) throws Exception {

        if (xmlConfig.isPresent()) {
            configureFractionsFromXML(config, list);
        } else {
            configureInterfaces(config, list);
            configureSocketBindingGroups(config, list);
            configureFractions(config, list);
        }
    }

    private void configureInterfaces(Container config, List<ModelNode> list) {
        List<Interface> ifaces = config.ifaces();

        for (Interface each : ifaces) {
            configureInterface(each, list);
        }
    }

    private void configureInterface(Interface iface, List<ModelNode> list) {
        ModelNode node = new ModelNode();

        node.get(OP).set(ADD);
        node.get(OP_ADDR).set("interface", iface.getName());
        node.get(INET_ADDRESS).set(new ValueExpression(iface.getExpression()));

        list.add(node);
    }

    private void configureSocketBindingGroups(Container config, List<ModelNode> list) {
        List<SocketBindingGroup> groups = config.socketBindingGroups();

        for (SocketBindingGroup each : groups) {
            configureSocketBindingGroup(each, list);
        }
    }

    private void configureSocketBindingGroup(SocketBindingGroup group, List<ModelNode> list) {
        ModelNode node = new ModelNode();

        PathAddress address = PathAddress.pathAddress("socket-binding-group", group.name());
        node.get(OP).set(ADD);
        node.get(OP_ADDR).set(address.toModelNode());
        node.get(DEFAULT_INTERFACE).set(group.defaultInterface());
        node.get(PORT_OFFSET).set(new ValueExpression(group.portOffsetExpression()));
        list.add(node);

        configureSocketBindings(address, group, list);

    }

    private void configureSocketBindings(PathAddress address, SocketBindingGroup group, List<ModelNode> list) {
        List<SocketBinding> socketBindings = group.socketBindings();

        for (SocketBinding each : socketBindings) {
            configureSocketBinding(address, each, list);
        }

        List<OutboundSocketBinding> outboundSocketBindings = group.outboundSocketBindings();

        for (OutboundSocketBinding each : outboundSocketBindings) {
            configureSocketBinding(address, each, list);
        }
    }

    private void configureSocketBinding(PathAddress address, SocketBinding binding, List<ModelNode> list) {

        ModelNode node = new ModelNode();

        node.get(OP_ADDR).set(address.append("socket-binding", binding.name()).toModelNode());
        node.get(OP).set(ADD);
        node.get(PORT).set(new ValueExpression(binding.portExpression()));
        if (binding.multicastAddress() != null) {
            node.get(MULTICAST_ADDRESS).set(binding.multicastAddress());
        }
        if (binding.multicastPortExpression() != null) {
            node.get(MULTICAST_PORT).set(new ValueExpression(binding.multicastPortExpression()));
        }

        list.add(node);
    }

    private void configureSocketBinding(PathAddress address, OutboundSocketBinding binding, List<ModelNode> list) {

        ModelNode node = new ModelNode();

        node.get(OP_ADDR).set(address.append("remote-destination-outbound-socket-binding", binding.name()).toModelNode());
        node.get(OP).set(ADD);
        node.get(HOST).set(new ValueExpression(binding.remoteHostExpression()));
        node.get(PORT).set(new ValueExpression(binding.remotePortExpression()));

        list.add(node);
    }

    @SuppressWarnings("unchecked")
    private void configureFractionsFromXML(Container container, List<ModelNode> operationList) throws Exception {

        StandaloneXmlParser parser = new StandaloneXmlParser();

        FractionProcessor<StandaloneXmlParser> consumer = (p, cfg, fraction) -> {
            try {
                if (cfg.getSubsystemParsers().isPresent()) {
                    Map<QName, XMLElementReader<List<ModelNode>>> fractionParsers =
                            (Map<QName, XMLElementReader<List<ModelNode>>>) cfg.getSubsystemParsers().get();

                    fractionParsers.forEach(p::addDelegate);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // collect parsers
        visitFractions(container, parser, consumer);

        // parse the configurations
        List<ModelNode> parseResult = parser.parse(xmlConfig.get());
        operationList.addAll(parseResult);

    }

    private void configureFractions(Container config, List<ModelNode> list) throws Exception {
        for (ServerConfiguration<Fraction> eachConfig : this.configList) {
            boolean found = false;
            for (Fraction eachFraction : config.fractions()) {
                if (eachConfig.getType().isAssignableFrom(eachFraction.getClass())) {
                    found = true;
                    list.addAll(eachConfig.getList(eachFraction));
                    break;
                }
            }
            if (!found && !eachConfig.isIgnorable()) {
                System.err.println("*** unable to find fraction for: " + eachConfig.getType());
            }
        }
    }

    /**
     * Wraps common iteration pattern over fraction and server configurations
     *
     * @param container
     * @param context   processing context (i.e. accumulator)
     * @param fn        a {@link org.wildfly.swarm.container.runtime.RuntimeServer.FractionProcessor} instance
     */
    private <T> void visitFractions(Container container, T context, FractionProcessor<T> fn) {
        OUTER:
        for (ServerConfiguration eachConfig : this.configList) {
            boolean found = false;
            INNER:
            for (Fraction eachFraction : container.fractions()) {
                if (eachConfig.getType().isAssignableFrom(eachFraction.getClass())) {
                    found = true;
                    fn.accept(context, eachConfig, eachFraction);
                    break INNER;
                }
            }
            if (!found && !eachConfig.isIgnorable()) {
                System.err.println("*** unable to find fraction for: " + eachConfig.getType());
            }

        }
    }

    private static final String BUILD_TIME_INDEX_NAME = "META-INF/swarm-jandex.idx";

    private SelfContainedContainer container = new SelfContainedContainer();

    private SimpleContentProvider contentProvider = new SimpleContentProvider();

    private ServiceContainer serviceContainer;

    private ModelControllerClient client;

    private RuntimeDeployer deployer;

    private Map<Class<? extends Fraction>, ServerConfiguration> configByFractionType = new ConcurrentHashMap<>();

    private List<ServerConfiguration<Fraction>> configList = new ArrayList<>();

    // optional XML config
    private Optional<URL> xmlConfig = Optional.empty();

    private BootstrapLogger LOG = BootstrapLogger.logger("org.wildfly.swarm.runtime.server");

    // TODO : still needed or merge error?
    private boolean debug;

    @FunctionalInterface
    interface FractionProcessor<T> {
        void accept(T t, ServerConfiguration config, Fraction fraction);
    }

    private static class ExtensionOpPriorityComparator implements Comparator<ModelNode> {
        @Override
        public int compare(ModelNode left, ModelNode right) {

            PathAddress leftAddr = PathAddress.pathAddress(left.get(OP_ADDR));
            PathAddress rightAddr = PathAddress.pathAddress(right.get(OP_ADDR));

            String leftOpName = left.require(OP).asString();
            String rightOpName = left.require(OP).asString();

            if (leftAddr.size() == 1 && leftAddr.getElement(0).getKey().equals(EXTENSION) && leftOpName.equals(ADD)) {
                return -1;
            }

            if (rightAddr.size() == 1 && rightAddr.getElement(0).getKey().equals(EXTENSION) && rightOpName.equals(ADD)) {
                return 1;
            }

            return 0;
        }
    }

    public interface Opener {
        void open();
    }
}
