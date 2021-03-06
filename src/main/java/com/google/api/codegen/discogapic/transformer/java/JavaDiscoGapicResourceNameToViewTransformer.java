/* Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.discogapic.transformer.java;

import static com.google.api.codegen.util.java.JavaTypeTable.JavaLangResolution.IGNORE_JAVA_LANG_CLASH;

import com.google.api.codegen.config.DiscoApiModel;
import com.google.api.codegen.config.DiscoveryField;
import com.google.api.codegen.config.DiscoveryMethodModel;
import com.google.api.codegen.config.GapicProductConfig;
import com.google.api.codegen.config.MethodConfig;
import com.google.api.codegen.config.PackageMetadataConfig;
import com.google.api.codegen.config.SingleResourceNameConfig;
import com.google.api.codegen.discogapic.SchemaTransformationContext;
import com.google.api.codegen.discogapic.transformer.DiscoGapicParser;
import com.google.api.codegen.discogapic.transformer.DocumentToViewTransformer;
import com.google.api.codegen.discovery.Method;
import com.google.api.codegen.discovery.Schema;
import com.google.api.codegen.gapic.GapicCodePathMapper;
import com.google.api.codegen.transformer.DiscoGapicInterfaceContext;
import com.google.api.codegen.transformer.FileHeaderTransformer;
import com.google.api.codegen.transformer.ImportTypeTable;
import com.google.api.codegen.transformer.SchemaTypeTable;
import com.google.api.codegen.transformer.StandardImportSectionTransformer;
import com.google.api.codegen.transformer.SurfaceNamer;
import com.google.api.codegen.transformer.java.JavaFeatureConfig;
import com.google.api.codegen.transformer.java.JavaSchemaTypeNameConverter;
import com.google.api.codegen.transformer.java.JavaSurfaceNamer;
import com.google.api.codegen.util.Name;
import com.google.api.codegen.util.SymbolTable;
import com.google.api.codegen.util.java.JavaNameFormatter;
import com.google.api.codegen.util.java.JavaTypeTable;
import com.google.api.codegen.viewmodel.StaticLangApiResourceNameFileView;
import com.google.api.codegen.viewmodel.StaticLangApiResourceNameView;
import com.google.api.codegen.viewmodel.StaticLangMemberView;
import com.google.api.codegen.viewmodel.ViewModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* Creates the ViewModel for Discovery-Doc-based ResourceName and ResourceTypeName Java classes. */
public class JavaDiscoGapicResourceNameToViewTransformer implements DocumentToViewTransformer {
  private final GapicCodePathMapper pathMapper;
  private final StandardImportSectionTransformer importSectionTransformer =
      new StandardImportSectionTransformer();
  private final FileHeaderTransformer fileHeaderTransformer =
      new FileHeaderTransformer(importSectionTransformer);
  private final JavaNameFormatter nameFormatter = new JavaNameFormatter();
  private static Set<String> reservedKeywords = new HashSet<>();

  static {
    reservedKeywords.addAll(JavaNameFormatter.RESERVED_IDENTIFIER_SET);
    reservedKeywords.add("Builder");
  }

  private static final String RESOURCE_NAME_TEMPLATE_FILENAME = "java/resource_name.snip";

  public JavaDiscoGapicResourceNameToViewTransformer(
      GapicCodePathMapper pathMapper, PackageMetadataConfig packageMetadataConfig) {
    this.pathMapper = pathMapper;
  }

  @Override
  public List<String> getTemplateFileNames() {
    return Arrays.asList(RESOURCE_NAME_TEMPLATE_FILENAME);
  }

  @Override
  public List<ViewModel> transform(DiscoApiModel apiModel, GapicProductConfig productConfig) {
    List<ViewModel> surfaceRequests = new ArrayList<>();
    String packageName = productConfig.getPackageName();
    SurfaceNamer surfaceNamer = new JavaSurfaceNamer(packageName, packageName, nameFormatter);

    DiscoGapicInterfaceContext context =
        DiscoGapicInterfaceContext.createWithoutInterface(
            apiModel,
            productConfig,
            createTypeTable(productConfig.getPackageName(), surfaceNamer),
            surfaceNamer,
            JavaFeatureConfig.newBuilder().enableStringFormatFunctions(true).build());

    // Keep track of which name patterns have been generated to avoid duplicate classes.
    Set<String> namePatterns = new HashSet<>();

    for (String interfaceName : productConfig.getInterfaceConfigMap().keySet()) {
      SchemaTransformationContext requestContext =
          SchemaTransformationContext.create(interfaceName, context.getSchemaTypeTable(), context);
      // Maps a canonical resource name pattern to any method that uses that pattern.
      Map<String, Method> namePatternsToMethod = new HashMap<>();

      for (MethodConfig methodConfig :
          productConfig.getInterfaceConfigMap().get(interfaceName).getMethodConfigs()) {
        Method method = ((DiscoveryMethodModel) methodConfig.getMethodModel()).getDiscoMethod();
        namePatternsToMethod.put(DiscoGapicParser.getCanonicalPath(method.flatPath()), method);
      }
      for (SingleResourceNameConfig nameConfig :
          productConfig.getInterfaceConfigMap().get(interfaceName).getSingleResourceNameConfigs()) {
        String namePattern = nameConfig.getNamePattern();
        if (namePatterns.contains(namePattern)) {
          continue;
        }
        Method method = namePatternsToMethod.get(namePattern);
        StaticLangApiResourceNameView resourceNameView =
            generateResourceNameClass(requestContext, method, nameConfig);
        surfaceRequests.add(generateResourceNameFile(requestContext, resourceNameView));

        namePatterns.add(nameConfig.getNamePattern());
      }
    }

    Collections.sort(
        surfaceRequests,
        new Comparator<ViewModel>() {
          @Override
          public int compare(ViewModel o1, ViewModel o2) {
            return String.CASE_INSENSITIVE_ORDER.compare(o1.outputPath(), o2.outputPath());
          }
        });
    return surfaceRequests;
  }

  /* Given a ResourceName view, creates a top-level ResourceName file view. */
  private StaticLangApiResourceNameFileView generateResourceNameFile(
      SchemaTransformationContext context, StaticLangApiResourceNameView messageView) {
    StaticLangApiResourceNameFileView.Builder apiFile =
        StaticLangApiResourceNameFileView.newBuilder();
    apiFile.templateFileName(RESOURCE_NAME_TEMPLATE_FILENAME);
    addResourceNameClassImports(context.getImportTypeTable());
    apiFile.resourceName(messageView);

    String outputPath = pathMapper.getOutputPath(null, context.getDocContext().getProductConfig());
    apiFile.outputPath(outputPath + File.separator + messageView.typeName() + ".java");

    apiFile.fileHeader(fileHeaderTransformer.generateFileHeader(context, messageView.typeName()));

    return apiFile.build();
  }

  private StaticLangApiResourceNameView generateResourceNameClass(
      SchemaTransformationContext context, Method method, SingleResourceNameConfig nameConfig) {
    StaticLangApiResourceNameView.Builder resourceNameView =
        StaticLangApiResourceNameView.newBuilder();

    SymbolTable symbolTable = SymbolTable.fromSeed(reservedKeywords);

    String resourceName =
        nameFormatter.localVarName(DiscoGapicParser.getResourceNameName(nameConfig));
    resourceNameView.name(resourceName);
    resourceNameView.typeName(nameFormatter.publicClassName(Name.anyCamel(resourceName)));
    resourceNameView.pathTemplate(nameConfig.getNamePattern());

    List<StaticLangMemberView> properties = new LinkedList<>();
    for (Map.Entry<String, Schema> entry : method.parameters().entrySet()) {
      if (entry.getValue().isPathParam()) {
        Schema param = entry.getValue();
        properties.add(schemaToParamView(context, param, symbolTable));
      }
    }
    Collections.sort(properties);
    resourceNameView.pathParams(properties);

    return resourceNameView.build();
  }

  // Transforms a request/response Schema object into a StaticLangApiResourceNameView.
  private StaticLangMemberView schemaToParamView(
      SchemaTransformationContext context, Schema schema, SymbolTable symbolTable) {
    StaticLangMemberView.Builder paramView = StaticLangMemberView.newBuilder();
    DiscoveryField field = DiscoveryField.create(schema, context.getDocContext().getApiModel());
    String typeName = context.getSchemaTypeTable().getAndSaveNicknameFor(field);
    paramView.name(symbolTable.getNewSymbol(schema.getIdentifier()));
    paramView.typeName(typeName);
    paramView.fieldGetFunction(
        context
            .getDiscoGapicNamer()
            .getResourceGetterName(schema.getIdentifier(), context.getNamer()));
    paramView.fieldSetFunction(
        context
            .getDiscoGapicNamer()
            .getResourceSetterName(
                schema.getIdentifier(),
                SurfaceNamer.Cardinality.ofRepeated(schema.type().equals(Schema.Type.ARRAY)),
                context.getNamer()));
    return paramView.build();
  }

  private void addResourceNameClassImports(ImportTypeTable typeTable) {
    typeTable.getAndSaveNicknameFor("com.google.api.core.BetaApi");
    typeTable.getAndSaveNicknameFor("com.google.common.base.Preconditions");
    typeTable.getAndSaveNicknameFor("com.google.common.collect.ImmutableMap");
    typeTable.getAndSaveNicknameFor("com.google.api.pathtemplate.PathTemplate");
    typeTable.getAndSaveNicknameFor("com.google.api.resourcenames.ResourceName");
    typeTable.getAndSaveNicknameFor("com.google.api.resourcenames.ResourceNameFactory");
    typeTable.getAndSaveNicknameFor("java.io.IOException");
    typeTable.getAndSaveNicknameFor("java.util.Collections");
    typeTable.getAndSaveNicknameFor("java.util.HashMap");
    typeTable.getAndSaveNicknameFor("java.util.List");
    typeTable.getAndSaveNicknameFor("java.util.Map");
    typeTable.getAndSaveNicknameFor("java.util.Objects");
    typeTable.getAndSaveNicknameFor("java.util.Set");
    typeTable.getAndSaveNicknameFor("javax.annotation.Generated");
  }

  private SchemaTypeTable createTypeTable(String implicitPackageName, SurfaceNamer languageNamer) {
    return new SchemaTypeTable(
        new JavaTypeTable(implicitPackageName, IGNORE_JAVA_LANG_CLASH),
        new JavaSchemaTypeNameConverter(implicitPackageName, nameFormatter),
        languageNamer);
  }
}
