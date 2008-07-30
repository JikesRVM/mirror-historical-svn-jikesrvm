#!/bin/bash

cp ../../.ant.properties ../../ant.properties.bck

echo host.name=ia32-linux > ../../.ant.properties
echo target.name=ia32-linux >> ../../.ant.properties
echo config.name=production >> ../../.ant.properties


cd ../../
ant real-clean
ant build

cd build/primordials

./getexternals.pl ../../MMTk/src/ ../../rvm/ ../../common/ ../../generated/|grep java|./primhelper.pl ../../components/openjdk/b29/src/share/classes/ >OpenJDK.txt

cd ../../
mv ant.properties.bck .ant.properties
cd build/primordials

