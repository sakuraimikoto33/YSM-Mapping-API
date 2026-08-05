@{
    Name = "YSM-Mapping-API"
    MainOnlyPaths = @(
        ".github/workflows/release.yml"
    )
    SharedPaths = @(
        "AGENTS.md"
        ".agents"
        ".github"
        ".gitignore"
        "gradle"
        "gradlew"
        "gradlew.bat"
        "api-core"
        "analysis-core"
        "mapping-tool"
    )
    VersionPaths = @(
        "api"
        "common"
        "fabric"
        "forge"
        "neoforge"
    )
    MixedPaths = @(
        "README.md"
        "build.gradle.kts"
        "settings.gradle.kts"
        "gradle.properties"
    )
    ForbiddenTrackedPatterns = @(
        "(^|/)local-ysm/"
        "(^|/)ysm-analysis/"
        "(^|/)test-fixtures/private/"
        "(^|/)build/reports/"
        "(^|/)ysm_mapping_api/reference/"
        "(^|/)(?:decompile[d]?|private-reports?|runtime-names?|whole-jar-graphs?)/"
        "(^|/)(?:registry-report|name-report|whole-jar-graph)(?:[-_.].*)?\.(?:json|txt|csv|tsv|dot|graphml)$"
        "^(?!gradle/wrapper/gradle-wrapper\.jar$).+\.jar$"
        "\.(?:dll|so|dylib)$"
    )
    PropagationSiblingRepositories = @()
    RepositoryVerifier = ".agents/skills/maintain-ysm-mapping-contract/scripts/verify-mapping-contract.ps1"
}
