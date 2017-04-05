package uk.gov.justice.services.adapters.rest.generator;

import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static uk.gov.justice.services.adapter.rest.processor.response.ResponseStrategies.ACCEPTED_STATUS_NO_ENTITY_RESPONSE_STRATEGY;
import static uk.gov.justice.services.adapter.rest.processor.response.ResponseStrategies.FILE_STREAM_RETURNING_RESPONSE_STRATEGY;
import static uk.gov.justice.services.adapter.rest.processor.response.ResponseStrategies.OK_STATUS_ENVELOPE_ENTITY_RESPONSE_STRATEGY;
import static uk.gov.justice.services.adapter.rest.processor.response.ResponseStrategies.OK_STATUS_ENVELOPE_PAYLOAD_ENTITY_RESPONSE_STRATEGY;
import static uk.gov.justice.services.adapters.rest.generator.Generators.byMimeTypeOrder;
import static uk.gov.justice.services.adapters.rest.generator.Generators.resourceImplementationNameOf;
import static uk.gov.justice.services.adapters.rest.generator.Generators.resourceInterfaceNameOf;
import static uk.gov.justice.services.adapters.rest.helper.Multiparts.isMultipartResource;
import static uk.gov.justice.services.core.annotation.Component.QUERY_CONTROLLER;
import static uk.gov.justice.services.core.annotation.Component.QUERY_VIEW;
import static uk.gov.justice.services.generators.commons.config.GeneratorProperties.serviceComponentOf;
import static uk.gov.justice.services.generators.commons.helper.Actions.isSupportedActionType;
import static uk.gov.justice.services.generators.commons.helper.Actions.isSupportedActionTypeWithResponseTypeOnly;
import static uk.gov.justice.services.generators.commons.helper.Actions.isSynchronousAction;
import static uk.gov.justice.services.generators.commons.helper.Names.DEFAULT_ANNOTATION_PARAMETER;
import static uk.gov.justice.services.generators.commons.helper.Names.GENERIC_PAYLOAD_ARGUMENT_NAME;
import static uk.gov.justice.services.generators.commons.helper.Names.RESOURCE_PACKAGE_NAME;
import static uk.gov.justice.services.generators.commons.helper.Names.buildResourceMethodName;
import static uk.gov.justice.services.generators.commons.helper.Names.buildResourceMethodNameWithNoMimeType;
import static uk.gov.justice.services.generators.commons.helper.Names.packageNameOf;

import uk.gov.justice.raml.core.GeneratorConfig;
import uk.gov.justice.services.adapter.rest.mapping.ActionMapper;
import uk.gov.justice.services.adapter.rest.multipart.FileInputDetailsFactory;
import uk.gov.justice.services.adapter.rest.parameter.ParameterCollectionBuilder;
import uk.gov.justice.services.adapter.rest.parameter.ParameterCollectionBuilderFactory;
import uk.gov.justice.services.adapter.rest.parameter.ParameterType;
import uk.gov.justice.services.adapter.rest.processor.RestProcessor;
import uk.gov.justice.services.adapters.rest.uri.BaseUri;
import uk.gov.justice.services.core.annotation.Adapter;
import uk.gov.justice.services.core.annotation.Component;
import uk.gov.justice.services.core.annotation.CustomAdapter;
import uk.gov.justice.services.core.interceptor.InterceptorChainProcessor;
import uk.gov.justice.services.messaging.logging.HttpTraceLoggerHelper;
import uk.gov.justice.services.messaging.logging.TraceLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.json.JsonObject;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.parameter.FormParameter;
import org.raml.model.parameter.QueryParameter;
import org.raml.model.parameter.UriParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal code generation class for generating the JAX-RS implementation class that implements the
 * Interface generated by the {@link JaxRsInterfaceGenerator}.
 */
class JaxRsImplementationGenerator {

    private static final String PARAMS_PUT_REQUIRED_STATEMENT_FORMAT = "$L.putRequired($S, $N, $T.$L)";
    private static final String PARAMS_PUT_OPTIONAL_STATEMENT_FORMAT = "$L.putOptional($S, $N, $T.$L)";

    private static final String REST_PROCESSOR_NO_PAYLOAD_METHOD_STATEMENT = "return restProcessor.process($S, $L::process, $L.actionOf($S, \"GET\", headers), headers, $L.parameters())";
    private static final String REST_PROCESSOR_PAYLOAD_METHOD_STATEMENT = "return restProcessor.process($S, $L::process, $L.actionOf($S, $S, headers), %s, headers, $L.parameters())";
    private static final String REST_PROCESSOR_MULTIPART_METHOD_STATEMENT = "return restProcessor.process($S, $L::process, $L.actionOf($S, $S, headers), headers, $L.parameters(), $L.createFileInputDetailsFrom($L, $L))";

    private static final String MULTIPART_FORM_DATA_INPUT = "multipartFormDataInput";

    private static final String VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE = "validParameterCollectionBuilder";
    private static final String PART_DEFINITIONS_VARIABLE = "partDefinitions";

    private static final String INTERCEPTOR_CHAIN_PROCESSOR_FIELD = "interceptorChainProcessor";
    private static final String ACTION_MAPPER_FIELD = "actionMapper";
    private static final String FILE_INPUT_DETAILS_FACTORY_FIELD = "fileInputDetailsFactory";
    private static final String VALID_PARAMETER_COLLECTION_BUILDER_FACTORY_FIELD = "validParameterCollectionBuilderFactory";
    private static final String TRACE_LOGGER_FIELD = "traceLogger";
    private static final String HTTP_TRACE_LOGGER_HELPER_FIELD = "httpTraceLoggerHelper";

    private final GeneratorConfig configuration;

    /**
     * Constructor.
     *
     * @param configuration the generator configuration
     */
    JaxRsImplementationGenerator(final GeneratorConfig configuration) {
        this.configuration = configuration;
    }

    /**
     * Generate an implementation class for each resource in raml.
     *
     * @param raml to generate as implementation classes
     * @return a list of {@link TypeSpec} that represent the implementation classes
     */
    List<TypeSpec> generateFor(final Raml raml) {
        final Collection<Resource> resources = raml.getResources().values();
        return resources.stream()
                .map(resource -> generateFor(resource, new BaseUri(raml.getBaseUri())))
                .collect(Collectors.toList());
    }

    /**
     * Create an implementation class for the specified {@link Resource}
     *
     * @param resource  the resource to generate as an implementation class
     * @param baseUri base uri of the raml
     * @return a {@link TypeSpec} that represents the implementation class
     */
    private TypeSpec generateFor(final Resource resource, final BaseUri baseUri) {
        final TypeSpec.Builder classSpecBuilder = classSpecFor(resource, baseUri);

        resource.getActions().values().forEach(action -> {
            final Optional<String> component = baseUri.component();
            classSpecBuilder.addMethods(methodsFor(action, component));
        });

        return classSpecBuilder.build();
    }

    /**
     * Creates a {@link TypeSpec.Builder} from an initial template of an implementation class
     *
     * @param resource  the resource to generate as an implementation class
     * @param baseUri the optional component for this class
     * @return a {@link TypeSpec.Builder} that represents the implementation class
     */
    private TypeSpec.Builder classSpecFor(final Resource resource, final BaseUri baseUri) {
        final String className = resourceImplementationNameOf(resource, baseUri);

        final Optional<String> component = baseUri.component();
        return classBuilder(className)
                .addSuperinterface(interfaceClassNameFor(resource, baseUri))
                .addModifiers(PUBLIC)
                .addAnnotation(componentAnnotationFor(component))
                .addField(loggerConstantField(className))
                .addField(FieldSpec.builder(RestProcessor.class, "restProcessor")
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(ActionMapper.class, ACTION_MAPPER_FIELD)
                        .addAnnotation(Inject.class)
                        .addAnnotation(AnnotationSpec.builder(Named.class)
                                .addMember(DEFAULT_ANNOTATION_PARAMETER, "$S", className + "ActionMapper").build())
                        .build())
                .addField(FieldSpec.builder(InterceptorChainProcessor.class, INTERCEPTOR_CHAIN_PROCESSOR_FIELD)
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(HttpHeaders.class, "headers")
                        .addAnnotation(Context.class)
                        .build())
                .addField(FieldSpec.builder(FileInputDetailsFactory.class, FILE_INPUT_DETAILS_FACTORY_FIELD)
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(ParameterCollectionBuilderFactory.class, VALID_PARAMETER_COLLECTION_BUILDER_FACTORY_FIELD)
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(TraceLogger.class, TRACE_LOGGER_FIELD)
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(HttpTraceLoggerHelper.class, HTTP_TRACE_LOGGER_HELPER_FIELD)
                        .addAnnotation(Inject.class)
                        .build());
    }

    private boolean containsOctetStreamResponse(final Action action) {
        for (org.raml.model.Response response : action.getResponses().values()) {
            if (response.getBody() != null) {
                for (MimeType mimeType : response.getBody().values()) {
                    if (mimeType.getType().equals(APPLICATION_OCTET_STREAM)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String nameOfResponseStrategyFor(final Action action, final Optional<String> component) {
        if (containsOctetStreamResponse(action)) {
            return FILE_STREAM_RETURNING_RESPONSE_STRATEGY;
        }

        if (!isSynchronousAction(action)) {
            return ACCEPTED_STATUS_NO_ENTITY_RESPONSE_STRATEGY;
        }

        if (isPresentAndRequiresEnvelopeEntityResponse(component)) {
            return OK_STATUS_ENVELOPE_ENTITY_RESPONSE_STRATEGY;
        }

        return OK_STATUS_ENVELOPE_PAYLOAD_ENTITY_RESPONSE_STRATEGY;
    }

    /**
     * Generate an {@link Adapter} annotation from the Component or a {@link CustomAdapter}
     * annotation from the service component configuration parameter.
     *
     * @param component the Optional Component
     * @return the AnnotationSpec
     */
    private AnnotationSpec componentAnnotationFor(final Optional<String> component) {
        return component
                .map(value ->
                        AnnotationSpec.builder(Adapter.class)
                                .addMember(DEFAULT_ANNOTATION_PARAMETER, "$T.$L", Component.class, value)
                                .build())
                .orElseGet(() ->
                        AnnotationSpec.builder(CustomAdapter.class)
                                .addMember(DEFAULT_ANNOTATION_PARAMETER, "$S", serviceComponentOf(configuration))
                                .build());
    }

    /**
     * Process the body or bodies for each httpAction.
     *
     * @param action the httpAction to process
     * @param component
     * @return the list of {@link MethodSpec} that represents each method for the httpAction
     */
    private List<MethodSpec> methodsFor(final Action action, final Optional<String> component) {
        final ActionType actionType = action.getType();
        final String responseStrategyName = nameOfResponseStrategyFor(action, component);
        if (isSupportedActionType(actionType)) {
            if (isSupportedActionTypeWithResponseTypeOnly(actionType)) {
                return singletonList(processNoActionBody(action, responseStrategyName));
            } else {
                return processOneOrMoreActionBodies(action, responseStrategyName);
            }
        }

        throw new IllegalStateException(format("Unsupported httpAction type %s", actionType));
    }

    /**
     * Process an httpAction with no body.
     *
     * @param action the httpAction to process
     * @return the {@link MethodSpec} that represents the method for the httpAction
     */
    private MethodSpec processNoActionBody(final Action action, final String responseStrategyName) {
        final String resourceMethodName = buildResourceMethodNameWithNoMimeType(action);
        return generateGetResourceMethod(resourceMethodName, action, responseStrategyName);
    }

    /**
     * Process an httpAction with one or more bodies.
     *
     * @param action the httpAction to process
     * @return the list of {@link MethodSpec} that represents each method for the httpAction
     */
    private List<MethodSpec> processOneOrMoreActionBodies(final Action action, final String responseStrategyName) {
        return action.getBody().values().stream()
                .sorted(byMimeTypeOrder())
                .map(bodyMimeType -> buildMethodSpecForMimeType(action, bodyMimeType, responseStrategyName))
                .collect(Collectors.toList());
    }

    /**
     * Build a method specification for the body mime type of an action
     *
     * @param action       - the action
     * @param bodyMimeType - the mime type to process
     * @return - a method specification
     */
    private MethodSpec buildMethodSpecForMimeType(final Action action, final MimeType bodyMimeType, final String responseStrategyName) {
        final String resourceMethodName = buildResourceMethodName(action, bodyMimeType);
        return generateResourceMethod(resourceMethodName, action, bodyMimeType, responseStrategyName);
    }

    /**
     * Generate the interface class name for implementation class
     *
     * @param resource generate for resource
     * @param baseUri
     * @return the {@link ClassName} of the interface
     */
    private ClassName interfaceClassNameFor(final Resource resource, final BaseUri baseUri) {
        return ClassName.get(packageNameOf(configuration, RESOURCE_PACKAGE_NAME), resourceInterfaceNameOf(resource, baseUri));
    }

    /**
     * Generate the Logger constant field.
     *
     * @return the {@link FieldSpec} that represents the generated field
     */
    private FieldSpec loggerConstantField(final String className) {
        final ClassName classLoggerFactory = ClassName.get(LoggerFactory.class);
        return FieldSpec.builder(Logger.class, "LOGGER")
                .addModifiers(PRIVATE, STATIC, FINAL)
                .initializer(
                        CodeBlock.builder()
                                .add(format("$L.getLogger(%s.class)", className), classLoggerFactory).build()
                )
                .build();
    }

    /**
     * Generate a POST or PUT resource method
     *
     * @param resourceMethodName - the name of this method
     * @param action             - the action to retrieve query and path parameters.
     * @param bodyMimeType       - the mime type to decide if payload parameter is required
     * @return the method
     */
    private MethodSpec generateResourceMethod(final String resourceMethodName,
                                              final Action action,
                                              final MimeType bodyMimeType, final String responseStrategyName) {


        if (isMultipartResource(bodyMimeType)) {
            return generateMultipartResourceMethod(resourceMethodName, action, bodyMimeType, responseStrategyName);
        } else {
            return generateStandardResourceMethod(resourceMethodName, action, bodyMimeType, responseStrategyName);
        }
    }

    /**
     * Generate standard POST or PUT resource method
     *
     * @param resourceMethodName - the name of this method
     * @param action             - the action to retrieve query and path parameters.
     * @param bodyMimeType       - the mime type to decide if payload parameter is required
     * @param responseStrategy   - the response strategy for the method
     * @return the method
     */
    private MethodSpec generateStandardResourceMethod(final String resourceMethodName,
                                                      final Action action,
                                                      final MimeType bodyMimeType,
                                                      final String responseStrategy) {
        final Map<String, QueryParameter> queryParams = action.getQueryParameters();
        final Map<String, UriParameter> pathParams = action.getResource().getUriParameters();

        final boolean hasPayload = bodyMimeType.getSchema() != null;
        final String payloadStatementPart = hasPayload ? "$T.of(entity)" : "$T.empty()";

        final MethodSpec.Builder methodBuilder = generateGenericResourceMethod(resourceMethodName, queryParams, pathParams)
                .addCode(methodBody(pathParams, methodBodyForPostOrPut(resourceMethodName, payloadStatementPart, action.getType(), responseStrategy)));

        if (hasPayload) {
            methodBuilder.addParameter(payloadParameter());
        }

        return methodBuilder.build();
    }

    /**
     * Generate multipart POST or PUT resource method
     *
     * @param resourceMethodName - the name of this method
     * @param action             - the action to retrieve query and path parameters.
     * @param responseStrategy   - the response strategy for the method  @return the method
     */
    private MethodSpec generateMultipartResourceMethod(final String resourceMethodName,
                                                       final Action action,
                                                       final MimeType bodyMimeType,
                                                       final String responseStrategy) {
        final Map<String, QueryParameter> queryParams = action.getQueryParameters();
        final Map<String, UriParameter> pathParams = action.getResource().getUriParameters();

        final MethodSpec.Builder methodBuilder = generateGenericResourceMethod(resourceMethodName, queryParams, pathParams)
                .addCode(methodBody(pathParams, methodBodyForMultipartPost(resourceMethodName, action.getType(), bodyMimeType, responseStrategy)));

        methodBuilder.addParameter(ParameterSpec
                .builder(MultipartFormDataInput.class, MULTIPART_FORM_DATA_INPUT, FINAL)
                .build());

        return methodBuilder.build();
    }

    /**
     * Generate a GET resource method
     *
     * @param resourceMethodName - the name of this method
     * @param action             - the action to retrieve query and path parameters.
     * @return the method
     */
    private MethodSpec generateGetResourceMethod(final String resourceMethodName,
                                                 final Action action, final String responseStrategyName) {
        final Map<String, QueryParameter> queryParams = action.getQueryParameters();
        final Map<String, UriParameter> pathParams = action.getResource().getUriParameters();

        return generateGenericResourceMethod(resourceMethodName, queryParams, pathParams)
                .addCode(methodBody(pathParams, methodBodyForGet(queryParams, resourceMethodName, responseStrategyName)))
                .build();
    }

    private boolean isPresentAndRequiresEnvelopeEntityResponse(final Optional<String> component) {
        return component.isPresent() && (QUERY_CONTROLLER.equals(component.get()) || QUERY_VIEW.equals(component.get()));
    }

    /**
     * Generate a generic resource method
     *
     * @param resourceMethodName - the name of this method
     * @param queryParams        - the query params to support
     * @param pathParams         - the path params to support
     * @return the method builder
     */
    private MethodSpec.Builder generateGenericResourceMethod(final String resourceMethodName,
                                                             final Map<String, QueryParameter> queryParams,
                                                             final Map<String, UriParameter> pathParams) {
        return methodBuilder(resourceMethodName)
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addParameters(methodPathParams(pathParams))
                .addParameters(methodQueryParams(queryParams))
                .returns(Response.class);
    }

    /**
     * Supplier that produces code specific to the GET httpAction type.
     *
     * @param queryParams          the query parameters to add to a map
     * @param resourceMethodName   name of the resource method
     * @param responseStrategyName the name of the response strategy
     * @return the supplier that returns the {@link CodeBlock}
     */
    private Supplier<CodeBlock> methodBodyForGet(final Map<String, QueryParameter> queryParams,
                                                 final String resourceMethodName,
                                                 final String responseStrategyName) {
        return () -> CodeBlock.builder()
                .add(putAllQueryParamsInCollectionBuilder(queryParams))
                .addStatement(REST_PROCESSOR_NO_PAYLOAD_METHOD_STATEMENT,
                        responseStrategyName,
                        INTERCEPTOR_CHAIN_PROCESSOR_FIELD,
                        ACTION_MAPPER_FIELD,
                        resourceMethodName,
                        VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE)
                .build();
    }

    /**
     * Supplier that produces code specific to the POST or PUT httpAction type.
     *
     * @param resourceMethodName   name of the resource method
     * @param payloadStatementPart defines whether the statement has a payload or no payload
     * @param actionType           the HTTP action type for this method
     * @param responseStrategy     the response strategy to pass to the ResponseStrategyFactory
     * @return the supplier that returns the {@link CodeBlock}
     */
    private Supplier<CodeBlock> methodBodyForPostOrPut(final String resourceMethodName,
                                                       final String payloadStatementPart,
                                                       final ActionType actionType,
                                                       final String responseStrategy) {
        return () -> CodeBlock.builder()
                .addStatement(format(REST_PROCESSOR_PAYLOAD_METHOD_STATEMENT, payloadStatementPart),
                        responseStrategy,
                        INTERCEPTOR_CHAIN_PROCESSOR_FIELD,
                        ACTION_MAPPER_FIELD,
                        resourceMethodName,
                        actionType.toString(),
                        Optional.class,
                        VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE)
                .build();
    }

    /**
     * Supplier that produces code specific to a Multipart POST httpAction type.
     *
     * @param resourceMethodName name of the resource method
     * @param actionType         the HTTP action type for this method
     * @param bodyMimeType       the mime type to retrieve the file parts from
     * @param responseStrategy   the response strategy to pass to the ResponseStrategyFactory
     * @return the supplier that returns the {@link CodeBlock}
     */
    private Supplier<CodeBlock> methodBodyForMultipartPost(final String resourceMethodName,
                                                           final ActionType actionType,
                                                           final MimeType bodyMimeType,
                                                           final String responseStrategy) {

        final TypeName stringClassType = TypeName.get(String.class);
        final ParameterizedTypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), stringClassType);
        final TypeName arrayClassType = TypeName.get(ArrayList.class);

        return () -> CodeBlock.builder()
                .addStatement("final $T $L = new $T<>()", listType, PART_DEFINITIONS_VARIABLE, arrayClassType)
                .add(putAllFileFormPartsInPartDefinitionsBuilder(bodyMimeType))
                .addStatement(REST_PROCESSOR_MULTIPART_METHOD_STATEMENT,
                        responseStrategy,
                        INTERCEPTOR_CHAIN_PROCESSOR_FIELD,
                        ACTION_MAPPER_FIELD,
                        resourceMethodName,
                        actionType.toString(),
                        VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE,
                        FILE_INPUT_DETAILS_FACTORY_FIELD,
                        MULTIPART_FORM_DATA_INPUT,
                        PART_DEFINITIONS_VARIABLE)
                .build();
    }

    private CodeBlock putAllFileFormPartsInPartDefinitionsBuilder(final MimeType bodyMimeType) {
        final CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();

        final Map<String, List<FormParameter>> formParameters = bodyMimeType.getFormParameters();

        formParameters.keySet().forEach(fieldName ->
                codeBlockBuilder.addStatement("$L.add($S)", PART_DEFINITIONS_VARIABLE, fieldName));

        return codeBlockBuilder.build();
    }

    /**
     * General code that is for both GET and POST httpAction type methods.
     *
     * @return the {@link CodeBlock} representing the general code
     */
    private CodeBlock methodBody(final Map<String, UriParameter> pathParams, final Supplier<CodeBlock> supplier) {
        final ClassName classMapBuilderType = ClassName.get(ParameterCollectionBuilder.class);

        return CodeBlock.builder()
                .addStatement("final $T $L = $L.create()", classMapBuilderType, VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE, VALID_PARAMETER_COLLECTION_BUILDER_FACTORY_FIELD)
                .addStatement("$L.trace(LOGGER, () -> String.format(\"Received REST request with headers: %s\", $L.toHttpHeaderTrace(headers)))",
                        TRACE_LOGGER_FIELD, HTTP_TRACE_LOGGER_HELPER_FIELD)
                .add(putAllPathParamsInCollectionBuilder(pathParams.keySet()))
                .add(supplier.get())
                .build();
    }

    /**
     * Generate code to add all path parameters to the params map.
     *
     * @param paramNames the params to add to the map
     * @return the {@link CodeBlock} that represents the generated code
     */
    private CodeBlock putAllPathParamsInCollectionBuilder(final Set<String> paramNames) {
        final CodeBlock.Builder builder = CodeBlock.builder();

        paramNames.forEach(name ->
                builder.addStatement(PARAMS_PUT_REQUIRED_STATEMENT_FORMAT, VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE, name, name, ParameterType.class, "STRING")
        );

        return builder.build();
    }

    /**
     * Generate code to add all query parameters to the params map.
     *
     * @param parameters the params to add to the map
     * @return the {@link CodeBlock} that represents the generated code
     */
    private CodeBlock putAllQueryParamsInCollectionBuilder(final Map<String, QueryParameter> parameters) {
        final CodeBlock.Builder builder = CodeBlock.builder();

        parameters.entrySet().forEach(paramEntry -> {
                    final String name = paramEntry.getKey();
                    final ParameterType parameterType = ParameterType.valueOfQueryType(paramEntry.getValue().getType().name());

                    if (paramEntry.getValue().isRequired()) {
                        builder.addStatement(PARAMS_PUT_REQUIRED_STATEMENT_FORMAT, VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE, name, name, ParameterType.class, parameterType.name());
                    } else {
                        builder.addStatement(PARAMS_PUT_OPTIONAL_STATEMENT_FORMAT, VALID_PARAMETER_COLLECTION_BUILDER_VARIABLE, name, name, ParameterType.class, parameterType.name());
                    }
                }
        );

        return builder.build();
    }

    /**
     * JsonObject payload entity parameter.
     *
     * @return the the parameter
     */
    private ParameterSpec payloadParameter() {
        return ParameterSpec
                .builder(JsonObject.class, GENERIC_PAYLOAD_ARGUMENT_NAME, FINAL)
                .build();
    }

    /**
     * Generate method parameters for all the path params.
     *
     * @param pathParams the path params to generate
     * @return list of {@link ParameterSpec} that represent the method parameters
     */
    private List<ParameterSpec> methodPathParams(final Map<String, UriParameter> pathParams) {
        return pathParams.keySet().stream().map(name ->
                ParameterSpec
                        .builder(String.class, name, FINAL)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Generate method parameters for all the query params.
     *
     * @param queryParams the query params to generate
     * @return list of {@link ParameterSpec} that represent the method parameters
     */
    private List<ParameterSpec> methodQueryParams(final Map<String, QueryParameter> queryParams) {
        return queryParams.keySet().stream().map(name ->
                ParameterSpec.builder(String.class, name, FINAL)
                        .build())
                .collect(Collectors.toList());
    }


}
