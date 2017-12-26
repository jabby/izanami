#!/usr/bin/env bash
echo "Uploading izanami.jar"
curl -T ./izanami-server/target/scala-2.12/izanami.jar -u$BINTRAY_USER:$BINTRAY_PASSWORD -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami.jar' https://api.bintray.com/content/maif/binaries/izanami.jar/latest/izanami.jar
curl -T ./izanami-server/target/universal/izanami-1.0.0.zip -u$BINTRAY_USER:$BINTRAY_PASSWORD -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: izanami-dist' https://api.bintray.com/content/maif/binaries/izanami-dist/latest/izanami-dist.zip
