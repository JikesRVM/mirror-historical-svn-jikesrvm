#!/bin/bash
###################### Default build and arguments to test - may be overridden on command line ######################
if [ -z $build  ]; then
    build="ExtremeAssertionsOptAdaptiveSapphire"
fi
if [ -z $arguments ]; then
    arguments="-X:gc:verbose=8 -X:gc:threads=1 -X:gc:noFinalizer=true -X:gc:noReferenceTypes=true"
fi

###################### Machine specific locations - change if needed ######################
jvm98dir="/Users/laurence/Dropbox/Laurence/PhD/software/benchmarks/jvm98"
pseudojbb2005dir="/Users/laurence/Dropbox/Laurence/PhD/software/benchmarks/pseudojbb2005/pseudojbb05"
dacapo2006dir="/Users/laurence/Dropbox/Laurence/PhD/software/benchmarks/dacapo-2006-10"
rvmSrc="/Users/laurence/Dropbox/Laurence/PhD/software/mirrorGC/src/myHead.git"

###################### Helper functions ######################
function doBuild {
    echo "build=${build}_${arch}-${host}"
    echo "arguments=${arguments}"
    if [ $buildIfOld == "true" ]
    then
        if ! git status | grep "nothing to commit"; then
            echo "ERROR: Git repository not up to date"
            exit 1
        fi
        # Test if we actually need to build
        jikesRevision=`./dist/${build}_${arch}-${host}/rvm -showfullversion 2>&1 | grep 'svn (revision' | awk '{print $6}'`
        gitRevision=`git show | head -1 | awk '{print $2}'`
        if ! [[ $jikesRevision == $gitRevision ]]
        then
            # Clean config
            ant real-clean
            # Build as required
            ant -Dhost.name=${arch}-${host} -Dconfig.name=$build -Dsvn.revision="evision `git show | grep '^commit ' | awk '{print $2}'` built on `date`" -Dsvn.branchName="`git branch | grep '^* ' | awk '{print $2}'`"
        fi
        buildIfOld="false" # Only need to attempt compile once per invocation
    fi
}

function noBuild {
    buildIfOld="false" 
}

function processArgs {
# Arguments go: size minHeap maxHeap benchmark(s)
size=$1
echo $size
shift
minHeap=$1
shift
maxHeap=$1
shift
benchmarks=$@   # With multiple benchmarks most of the success / failure pattern matching won't work
}

function jvm98 {
    doBuild
    processArgs $@
    cd ${jvm98dir}
    rm .SUCCESS &> /dev/null
    echo "Running ${arguments} -Xms${minHeap} -Xmx${maxHeap} JVM98 -s${size} ${benchmarks}"
    date
    time ${rvmSrc}/dist/${build}_${arch}-${host}/rvm -showfullversion ${arguments} -Xms${minHeap} -Xmx${maxHeap} SpecApplication -s${size} ${benchmarks} 2>&1 | while read line
    do
            echo $line
            if [[ $line =~ "Finished in" ]]
            then
                    touch .SUCCESS
            fi
    done
    date
    echo "Finished running ${arguments} -Xms${minHeap} -Xmx${maxHeap} JVM98 -s${size} ${benchmarks}"

    if [ ! -f .SUCCESS ] 
        then 
            echo "FAILED";
            exit 1;
    fi
    rm .SUCCESS
    echo "----------------------------------------------------------------------------------------------------------"
}

function dacapo2006 {
    doBuild
    processArgs $@
    cd ${dacapo2006dir}
    rm .SUCCESS &> /dev/null
    echo "Running ${arguments} -Xms${minHeap} -Xmx${maxHeap} DaCapo2006 ${size} ${benchmarks}"
    date
    time ${rvmSrc}/dist/${build}_${arch}-${host}/rvm -showfullversion ${arguments} -Xms${minHeap} -Xmx${maxHeap} -jar dacapo-2006-10-MR2.jar -s ${size} ${benchmarks} 2>&1 | while read line
    do
            echo $line
            if [[ $line =~ "PASSED" ]] # Simple test due to interleaving of stdout and stderr
            then
                    touch .SUCCESS
            fi
    done
    date
    echo "Finished running  ${arguments} -Xms${minHeap} -Xmx${maxHeap} DaCapo2006 ${size} ${benchmarks}"

    if [ ! -f .SUCCESS ] 
        then 
            echo "FAILED";
            exit 1;
    fi
    rm .SUCCESS
    echo "----------------------------------------------------------------------------------------------------------"
}

function pseudojbb2005 {
    doBuild
    processArgs $@
    cd ${pseudojbb2005dir}
    rm .SUCCESS &> /dev/null
    echo "Running ${arguments} -Xms${minHeap} -Xmx${maxHeap} SPECjbb-${size}"
    date
    time ${rvmSrc}/dist/${build}_${arch}-${host}/rvm -showfullversion ${arguments} -Xms${minHeap} -Xmx${maxHeap} -cp src spec.jbb.JBBmain -propfile SPECjbb-${size}.props 2>&1 | while read line
    do
            echo $line
            if [[ $line =~ "INVALID run; Score is" ]]
            then
                    touch .SUCCESS
            fi
    done
    date
    echo "Finished running ${arguments} -Xms${minHeap} -Xmx${maxHeap} SPECjbb-${size}"

    if [ ! -f .SUCCESS ] 
        then 
            echo "FAILED";
            exit 1;
    fi
    rm .SUCCESS
    echo "----------------------------------------------------------------------------------------------------------"
}

###################### The benchmarks and configuration to run ######################
function fast {
    echo "----------------------------------------------------------------------------------------------------------"
    echo "| Running fast tests                                                                                     |"
    echo "----------------------------------------------------------------------------------------------------------"
    # Category benchmark size minHeap maxHeap
    jvm98 10 10M 20M _200_check
    dacapo2006 small 10M 200M hsqldb
    dacapo2006 small 10M 200M lusearch
    dacapo2006 small 10M 200M xalan
    dacapo2006 small 10M 200M eclipse
    pseudojbb2005 4x100 10M 256M -
}

function default {
    echo "----------------------------------------------------------------------------------------------------------"
    echo "| Starting default tests                                                                                  |"
    echo "----------------------------------------------------------------------------------------------------------"
    jvm98 10 10M 20M _200_check
    dacapo2006 default 10M 200M hsqldb
    dacapo2006 default 10M 200M lusearch
    dacapo2006 default 10M 200M xalan
    dacapo2006 default 10M 200M eclipse
    pseudojbb2005 4x2000 10M 300M -
}

function large {
    echo "----------------------------------------------------------------------------------------------------------"
    echo "| Starting large tests                                                                                  |"
    echo "----------------------------------------------------------------------------------------------------------"
    jvm98 10 10M 20M _200_check
    dacapo2006 large 10M 400M hsqldb
    dacapo2006 large 10M 250M lusearch
    dacapo2006 large 10M 400M xalan
    dacapo2006 large 10M 500M eclipse
    pseudojbb2005 8x50000 10M 600M -
}

###################### Main ######################
echo "*** Test script only, not suitable for timing runs ***"
date

# Detect what kind of machine we are running on
host="linux"    # Default to linux
arch="ia32"     # Default to ia32

case `uname` in
        Darwin) host="osx" ;;
        Linux)  host="linux" ;;
esac

buildIfOld="true" # By default compile if built version is older than last commit

size="" # Global var set by processArgs
minHeap="" # Global var set by processArgs
maxHeap="" # Global var set by processArgs
benchmarks="" # Global var set by processArgs

# Process list of command line arguments treating each argument as a command
for var in "$@"
do
        $var
done

# Default case for no arguments
if [ $# -eq 0 ]
then
        # No arguments just run fast tests
        fast
fi

# Finish up
echo "All done :-)"
date
exit
