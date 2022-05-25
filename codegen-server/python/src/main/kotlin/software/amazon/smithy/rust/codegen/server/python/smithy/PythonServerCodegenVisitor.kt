
/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy

import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.model.neighbor.Walker
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.InputTrait
import software.amazon.smithy.model.traits.OutputTrait
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerEnumGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerServiceGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerStructureGenerator
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenVisitor
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.DefaultPublicModules
import software.amazon.smithy.rust.codegen.smithy.RustCrate
import software.amazon.smithy.rust.codegen.smithy.SymbolVisitorConfig
import software.amazon.smithy.rust.codegen.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.smithy.generators.BuilderGenerator
import software.amazon.smithy.rust.codegen.smithy.generators.CodegenTarget
import software.amazon.smithy.rust.codegen.smithy.generators.implBlock
import software.amazon.smithy.rust.codegen.util.CommandFailed
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.hasTrait
import software.amazon.smithy.rust.codegen.util.runCommand

/**
 * Entrypoint for Python server-side code generation. This class will walk the in-memory model and
 * generate all the needed types by calling the accept() function on the available shapes.
 *
 * This class inherits from [ServerCodegenVisitor] since it uses most of the functionlities of the super class
 * and have to override the symbol provider with [PythonServerSymbolProvider].
 */
class PythonServerCodegenVisitor(context: PluginContext, codegenDecorator: RustCodegenDecorator) :
    ServerCodegenVisitor(context, codegenDecorator) {

    private val codegenScope =
        arrayOf(
            "SmithyPython" to PythonServerCargoDependency.SmithyHttpServerPython(codegenContext.runtimeConfig).asType(),
            "SmithyServer" to ServerCargoDependency.SmithyHttpServer(codegenContext.runtimeConfig).asType(),
            "pyo3" to PythonServerCargoDependency.PyO3.asType(),
            "pyo3asyncio" to PythonServerCargoDependency.PyO3Asyncio.asType(),
            "tokio" to PythonServerCargoDependency.Tokio.asType(),
            "tracing" to PythonServerCargoDependency.Tracing.asType()
        )

    init {
        val symbolVisitorConfig =
            SymbolVisitorConfig(
                runtimeConfig = settings.runtimeConfig,
                codegenConfig = settings.codegenConfig,
                handleRequired = true
            )
        val baseModel = baselineTransform(context.model)
        val service = settings.getService(baseModel)
        val (protocol, generator) =
            ServerProtocolLoader(
                codegenDecorator.protocols(
                    service.id,
                    ServerProtocolLoader.DefaultProtocols
                )
            )
                .protocolFor(context.model, service)
        protocolGeneratorFactory = generator
        model = generator.transformModel(codegenDecorator.transformModel(service, baseModel))
        val baseProvider = PythonCodegenServerPlugin.baseSymbolProvider(model, service, symbolVisitorConfig)
        // Override symbolProvider.
        symbolProvider =
            codegenDecorator.symbolProvider(generator.symbolProvider(model, baseProvider))

        // Override `codegenContext` which carries the symbolProvider.
        codegenContext = CodegenContext(model, symbolProvider, service, protocol, settings, target = CodegenTarget.SERVER)

        // Override `rustCrate` which carries the symbolProvider.
        rustCrate = RustCrate(context.fileManifest, symbolProvider, DefaultPublicModules, settings.codegenConfig)
        // Override `protocolGenerator` which carries the symbolProvider.
        protocolGenerator = protocolGeneratorFactory.buildProtocolGenerator(codegenContext)
    }

    /**
     * Execute code generation
     *
     * 1. Load the service from [RustSettings].
     * 2. Traverse every shape in the closure of the service.
     * 3. Loop through each shape and visit them (calling the override functions in this class)
     * 4. Call finalization tasks specified by decorators.
     * 5. Write the in-memory buffers out to files.
     *
     * The main work of code generation (serializers, protocols, etc.) is handled in `fn serviceShape` below.
     */
    override fun execute() {
        val service = settings.getService(model)
        logger.info(
            "[python-server-codegen] Generating Rust server for service $service, protocol ${codegenContext.protocol}"
        )
        val serviceShapes = Walker(model).walkShapes(service)
        serviceShapes.forEach { it.accept(this) }
        codegenDecorator.extras(codegenContext, rustCrate)
        renderPyO3Module(serviceShapes)
        rustCrate.finalize(
            settings,
            model,
            codegenDecorator.crateManifestCustomizations(codegenContext),
            codegenDecorator.libRsCustomizations(codegenContext, listOf()),
            // TODO(https://github.com/awslabs/smithy-rs/issues/1287): Remove once the server codegen is far enough along.
            requireDocs = false
        )
        try {
            "cargo fmt".runCommand(
                fileManifest.baseDir,
                timeout = settings.codegenConfig.formatTimeoutSeconds.toLong()
            )
        } catch (err: CommandFailed) {
            logger.warning(
                "[python-server-codegen] Failed to run cargo fmt: [${service.id}]\n${err.output}"
            )
        }
        logger.info("[python-server-codegen] Rust server generation complete!")
    }

    private fun renderPyO3Module(serviceShapes: Set<Shape>) {
        rustCrate.withModule(RustModule.public("python_module_export", "Export PyO3 symbols in the shared library")) { writer ->
            val libName = "lib${codegenContext.settings.moduleName}"
            writer.rustBlockTemplate(
                """
                ##[#{pyo3}::pymodule]
                ##[#{pyo3}(name = "$libName")]
                pub fn python_library(py: #{pyo3}::Python<'_>, m: &#{pyo3}::types::PyModule) -> #{pyo3}::PyResult<()>
            """,
                *codegenScope
            ) {
                // / Add local types from this crate.
                writer.rustTemplate(
                    """
                    let input = #{pyo3}::types::PyModule::new(py, "input")?;
                    let output = #{pyo3}::types::PyModule::new(py, "output")?;
                    let error = #{pyo3}::types::PyModule::new(py, "error")?;
                    let model = #{pyo3}::types::PyModule::new(py, "model")?;
                """,
                    *codegenScope
                )
                serviceShapes.forEach() { shape ->
                    val moduleType = moduleType(shape)
                    if (moduleType != null) {
                        writer.rustTemplate(
                            """
                            $moduleType.add_class::<crate::$moduleType::${shape.id.name}>()?;
                        """,
                            *codegenScope
                        )
                    }
                }
                writer.rustTemplate(
                    """
                    #{pyo3}::py_run!(py, input, "import sys; sys.modules['$libName.input'] = input");
                    m.add_submodule(input)?;
                    #{pyo3}::py_run!(py, output, "import sys; sys.modules['$libName.output'] = output");
                    m.add_submodule(output)?;
                    #{pyo3}::py_run!(py, error, "import sys; sys.modules['$libName.error'] = error");
                    m.add_submodule(error)?;
                    #{pyo3}::py_run!(py, model, "import sys; sys.modules['$libName.model'] = model");
                    m.add_submodule(model)?;
                """,
                    *codegenScope
                )

                // Add types from aws_smithy_http_server_python.
                writer.rustTemplate(
                    """
                    let types = #{pyo3}::types::PyModule::new(py, "types")?;
                    types.add_class::<#{SmithyPython}::types::Blob>()?;
                    types.add_class::<#{SmithyPython}::types::ByteStream>()?;
                    types.add_class::<#{SmithyPython}::types::DateTime>()?;
                    #{pyo3}::py_run!(
                        py,
                        types,
                        "import sys; sys.modules['$libName.types'] = types"
                    );
                    m.add_submodule(types)?;
                    """,
                    *codegenScope
                )

                // Add types from aws_smithy_http_server_python.
                writer.rustTemplate(
                    """
                    let socket = #{pyo3}::types::PyModule::new(py, "socket")?;
                    socket.add_class::<#{SmithyPython}::SharedSocket>()?;
                    #{pyo3}::py_run!(
                        py,
                        socket,
                        "import sys; sys.modules['$libName.types'] = socket"
                    );
                    m.add_submodule(socket)?;
                    """,
                    *codegenScope
                )
                writer.rustTemplate(
                    """
                    m.add_class::<crate::python_server_application::App>()?;
                    Ok(())
                """,
                    *codegenScope
                )
            }
        }
    }

    /**
     * Structure Shape Visitor
     *
     * For each structure shape, generate:
     * - A Rust structure for the shape ([StructureGenerator]).
     * - `pyo3::PyClass` trait implementation.
     * - A builder for the shape.
     *
     * This function _does not_ generate any serializers.
     */
    override fun structureShape(shape: StructureShape) {
        logger.info("[python-server-codegen] Generating a structure $shape")
        rustCrate.useShapeWriter(shape) { writer ->
            // Use Python specific structure generator that adds the #[pyclass] attribute
            // and #[pymethods] implementation.
            PythonServerStructureGenerator(model, codegenContext, symbolProvider, writer, shape).render(CodegenTarget.SERVER)
            val builderGenerator =
                BuilderGenerator(codegenContext.model, codegenContext.symbolProvider, shape)
            builderGenerator.render(writer)
            writer.implBlock(shape, symbolProvider) {
                builderGenerator.renderConvenienceMethod(this)
            }
        }
    }

    /**
     * String Shape Visitor
     *
     * Although raw strings require no code generation, enums are actually [EnumTrait] applied to string shapes.
     */
    override fun stringShape(shape: StringShape) {
        logger.info("[rust-server-codegen] Generating an enum $shape")
        shape.getTrait<EnumTrait>()?.also { enum ->
            rustCrate.useShapeWriter(shape) { writer ->
                PythonServerEnumGenerator(model, symbolProvider, writer, shape, enum, codegenContext.runtimeConfig).render()
            }
        }
    }

    /**
     * Generate service-specific code for the model:
     * - Serializers
     * - Deserializers
     * - Trait implementations
     * - Protocol tests
     * - Operation structures
     * - Python operation handlers
     */
    override fun serviceShape(shape: ServiceShape) {
        logger.info("[python-server-codegen] Generating a service $shape")
        PythonServerServiceGenerator(
            rustCrate,
            protocolGenerator,
            protocolGeneratorFactory.support(),
            protocolGeneratorFactory.protocol(codegenContext).httpBindingResolver,
            codegenContext,
        )
            .render()
    }

    private fun moduleType(shape: Shape): String? {
        return if (shape.hasTrait<InputTrait>()) {
            "input"
        } else if (shape.hasTrait<OutputTrait>()) {
            "output"
        } else if (shape.hasTrait<ErrorTrait>()) {
            "error"
        } else if (shape.hasTrait<EnumTrait>()) {
            "model"
        } else {
            null
        }
    }
}
