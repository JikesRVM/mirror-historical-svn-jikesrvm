#!/bin/bash
# Custom Jikes Build script
# pass no args to just build desired flavour or use "eclipse-project" to get a eclipse project
host="linux"    # Default to building on linux
arch=ia32       # Default to ia32
build=BaseBaseConcMS

case `uname` in
	Darwin) host="osx" ;;
	Linux)  host="linux" ;;
esac

if ! git status | grep "nothing to commit"; then
        echo "ERROR: Git repository not up to date"
        exit 1
fi

cd work
# Clean config
ant real-clean

# Build as required
ant -Dhost.name=${arch}-${host} -Dconfig.name=$build -Dsvn.revision="evision `git show | grep '^commit ' | awk '{print $2}'` built on `date`" -Dsvn.branchName="`git branch | grep '^* ' | awk '{print $2}'`" $@
