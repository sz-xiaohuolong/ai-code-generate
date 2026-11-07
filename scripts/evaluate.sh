#!/usr/bin/env bash
# 只编译生产入口并组装运行时依赖，不执行包含真实模型调用的全量测试。
set -euo pipefail
evaluation_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$evaluation_root"
evaluation_build="$evaluation_root/target/evaluation"
mkdir -p "$evaluation_build"
if ! ./mvnw -q -Pevaluation -Dmaven.test.skip=true compile dependency:build-classpath \
    -DincludeScope=runtime "-Dmdep.outputFile=$evaluation_build/classpath.txt" > "$evaluation_build/build.log" 2>&1; then
  printf '评测入口编译失败，请检查：%s\n' "$evaluation_build/build.log" >&2
  exit 3
fi
evaluation_classpath="$(cat "$evaluation_build/classpath.txt")"
evaluation_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$evaluation_java" -cp "$evaluation_build/classes:$evaluation_classpath" \
  com.xhl.aicodegenerate.evaluation.EvaluationMain "$@"
