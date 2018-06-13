# BASE should be a directory containing both gapic-generator and googleapis clones.
BASE=/Users/yuhanliu/projects

GAPIC_GENERATOR_DIR=$BASE/gapic-generator
GOOGLEAPIS_DIR=$BASE/googleapis
TEMP=/tmp/sample-gen
# output dirs
TEMP_ARTMAN=$TEMP/artman
TEMP_CLIENT=$TEMP/client

# NOTE: these next 5 variables must be consistent, and each time they are changed, e.g. language --> speech, you must re-run ./sample_gen.sh prepare

# This should point to the location of the target client library.
DESTINATION=$BASE/dpe-github/google-cloud-python/language

LANGUAGE=python

SERVICE_CONFIG=$GOOGLEAPIS_DIR/google/cloud/language/language_v1.yaml
# TODO: if these are unique to service config, discover them automatically
CLIENT_CONFIG=$GOOGLEAPIS_DIR/google/cloud/language/v1/language_gapic.yaml
ARTMAN_CONFIG=$GOOGLEAPIS_DIR/google/cloud/language/artman_language_v1.yaml



COMMAND=$1

if [ "$COMMAND" == "setup" ]
then
    echo "1. Install googleapis/artman."
    echo "2. Run ./gradlew fatJar in "$GAPIC_GENERATOR_DIR

# NOTE: you need to run ./sample_gen.sh prepare only once for each API.  here we are only using the descriptor and packaging config files generated by artman.
elif [ "$COMMAND" == "prepare" ]
then
    cd $GOOGLEAPIS_DIR && \
    artman \
    --config $ARTMAN_CONFIG \
    --output-dir $TEMP_ARTMAN \
    generate python_gapic

    echo "artman output saved in "$TEMP_ARTMAN

# NOTE: you must run ./sample_gen.sh build every time you update the .snip files.
elif [ "$COMMAND" == "build" ]
then
    cd $GAPIC_GENERATOR_DIR && \
    ./gradlew fatJar

# NOTE: you must run ./sample_gen.sh genearte every time you update the .snip files or the gapic config file (e.g. language_gapic.yaml).
elif [ "$COMMAND" == "generate" ]
then
    DESCRIPTOR=$TEMP_ARTMAN"/"`ls ${TEMP_ARTMAN} | grep desc`
    PACKAGING_CONFIG=$TEMP_ARTMAN"/"`ls ${TEMP_ARTMAN} | grep yaml`
    # echo $DESCRIPTOR
    # echo $PACKAGING_CONFIG
    rm -rf $TEMP_CLIENT

    cd $GAPIC_GENERATOR_DIR && ./gradlew runCodeGen \
        "-Pclargs=--descriptor_set=$DESCRIPTOR,--service_yaml=$SERVICE_CONFIG,--gapic_yaml=$CLIENT_CONFIG,--language=$LANGUAGE,--package_yaml2=$PACKAGING_CONFIG,--o=$TEMP_CLIENT"

    # cd $GAPIC_GENERATOR_DIR && java -cp build/libs/gapic-generator-0.*-fatjar.jar com.google.api.codegen.GeneratorMain \
    #     --descriptor_set=$DESCRIPTOR \
    #     --service_yaml=$SERVICE_CONFIG \
    #     --gapic_yaml=$CLIENT_CONFIG \
    #     --gapic_yaml=$LANGUAGE_CONFIG \
    #     --package_yaml2=$PACKAGING_CONFIG \
    #     --o=$TEMP_CLIENT

    rm -rf $DESTINATION/samples
    cp -r $TEMP_CLIENT/samples $DESTINATION

    echo "samples copied to "$DESTINATION"/samples"

else
    echo "setup, build, prepare, or generate"
fi