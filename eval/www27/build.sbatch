#!/bin/bash
# Build GenSPARQL on a compute node (the login node runs no builds) and write the
# classpath of the HTTP service for job.sbatch.
#   sbatch eval/www27/build.sbatch            (from the repository root)
#SBATCH --job-name=gs-build
#SBATCH --partition=fastlane
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=00:40:00
#SBATCH --output=gs-build-%j.out
set -euo pipefail
REPO=${REPO:-$HOME/workspace/GenSPARQL-www27}
export JAVA_HOME=$HOME/tools/jdk-21.0.8+9
export PATH=$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.11/bin:$PATH
cd "$REPO"
java -version 2>&1 | head -1
mvn -q -B install -DskipTests
mvn -q -B -pl gensparql-server dependency:build-classpath -Dmdep.outputFile=eval/www27/server.classpath
echo "built $(git rev-parse --short HEAD)"
