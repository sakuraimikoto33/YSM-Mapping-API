@{
    Name = "YSM-Mapping-API"
    ForbiddenTrackedPatterns = @(
        "(^|/)local-ysm/"
        "(^|/)ysm-analysis/"
        "(^|/)test-fixtures/private/"
        "(^|/)build/reports/"
        "(^|/)ysm_mapping_api/reference/"
        "(^|/)(?:decompile[d]?|private-reports?|runtime-names?|whole-jar-graphs?)/"
        "(^|/)(?:registry-report|name-report|whole-jar-graph)(?:[-_.].*)?\.(?:json|txt|csv|tsv|dot|graphml)`$"
        "^(?!gradle/wrapper/gradle-wrapper\.jar`$).+\.jar`$"
        "\.(?:dll|so|dylib)`$"
    )
    ValidationRepositories = @(
    )
    RepositoryVerifier = ".agents/skills/maintain-ysm-mapping-contract/scripts/verify-mapping-contract.ps1"
    RepositoryVerifierProfiles = @(
        "Main"
        "Minecraft"
    )
    MainValidation = @(
        "clean"
        "build"
    )
    MinecraftValidation = @(
        "clean"
        "build"
        "verifyDistributions"
    )
}
