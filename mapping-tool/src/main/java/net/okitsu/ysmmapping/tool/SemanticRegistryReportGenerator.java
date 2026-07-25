package net.okitsu.ysmmapping.tool;

import net.okitsu.ysmmapping.api.SymbolKind;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.internal.analysis.AnalysisProfile;
import net.okitsu.ysmmapping.internal.analysis.EquipmentSemanticAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.FixtureCatalog;
import net.okitsu.ysmmapping.internal.analysis.JarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.WholeJarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.YsmArtifact;
import net.okitsu.ysmmapping.internal.analysis.YsmClassIndex;
import net.okitsu.ysmmapping.internal.analysis.YsmSymbolKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Runs a catalog-defined ignored fixture set and emits aggregate-safe results. */
final class SemanticRegistryReportGenerator {
    private final AnalysisProfile profile;
    private final FixtureCatalog catalog;

    SemanticRegistryReportGenerator(AnalysisProfile profile, FixtureCatalog catalog) {
        this.profile = profile;
        this.catalog = catalog;
    }

    void generate(Path jarDirectory, Path output) throws Exception {
        List<String> mismatches = new ArrayList<>();
        Map<AnalysisProfile.Category, Set<String>> categoryKeys = categoryKeys();
        Set<String> serverlessKeys = categoryKeys.getOrDefault(
                AnalysisProfile.Category.SERVERLESS, Set.of());
        Set<String> directKeys = categoryKeys.getOrDefault(
                AnalysisProfile.Category.EQUIPMENT_DIRECT, Set.of());
        Set<String> equipmentKeys = new TreeSet<>(directKeys);
        equipmentKeys.addAll(categoryKeys.getOrDefault(
                AnalysisProfile.Category.EQUIPMENT_RELATED, Set.of()));

        List<Map<String, Object>> targets = new ArrayList<>();
        for (FixtureCatalog.Fixture fixture : catalog.fixtures()) {
            Path jar = jarDirectory.resolve(fixture.fileName()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) {
                mismatches.add("missing fixture=" + fixture.fileName());
                continue;
            }
            YsmClassIndex classIndex = YsmClassIndex.read(jar);
            var graph = new WholeJarStructureAnalyzer().analyze(classIndex);
            Set<String> serverlessResolved = new TreeSet<>();
            String serverlessDiagnostic = null;
            try {
                YsmArtifact artifact = new YsmArtifact(
                        "regression-" + fixture.loader() + '-' + fixture.ysmVersion(),
                        profile.minecraftVersion(), fixture.loader(), fixture.ysmVersion(),
                        "0".repeat(128));
                JarStructureAnalyzer.PartialAnalysis partial =
                        new JarStructureAnalyzer(profile).analyzePartial(artifact, classIndex);
                serverlessResolved.addAll(ids(partial.symbols()));
                if (!partial.diagnostics().isEmpty()) {
                    serverlessDiagnostic = partial.diagnostics().size()
                            + " symbol diagnostics";
                }
            } catch (Exception exception) {
                serverlessDiagnostic = exception.getMessage();
            }
            Map<YsmSymbolKey<?>, YsmResolvedSymbol> equipment =
                    new EquipmentSemanticAnalyzer().analyze(classIndex, profile, fixture.loader());
            Set<String> equipmentResolved = ids(equipment);

            Set<String> missingServerless = missing(serverlessKeys, serverlessResolved);
            Set<String> missingDirect = missing(directKeys, equipmentResolved);
            Set<String> missingEquipment = missing(equipmentKeys, equipmentResolved);
            if (!missingServerless.isEmpty()) {
                mismatches.add(fixture.id(profile.minecraftVersion()) + " serverless="
                        + serverlessResolved.size() + '/' + serverlessKeys.size());
            }
            if (catalog.expectations().equipmentDirectRequiredLoaders()
                    .contains(fixture.loader().toLowerCase(java.util.Locale.ROOT))
                    && !missingDirect.isEmpty()) {
                mismatches.add(fixture.id(profile.minecraftVersion()) + " equipment-direct="
                        + (directKeys.size() - missingDirect.size()) + '/' + directKeys.size());
            }
            if (catalog.expectations().equipmentFullRequiredYsmVersions()
                    .contains(fixture.ysmVersion().toLowerCase(java.util.Locale.ROOT))
                    && !missingEquipment.isEmpty()) {
                mismatches.add(fixture.id(profile.minecraftVersion()) + " equipment-total="
                        + (equipmentKeys.size() - missingEquipment.size()) + '/'
                        + equipmentKeys.size());
            }

            Map<String, Object> target = new LinkedHashMap<>();
            target.put("id", fixture.id(profile.minecraftVersion()));
            target.put("loader", fixture.loader());
            target.put("ysmVersion", fixture.ysmVersion());
            target.put("parsedClassCount", graph.classes().size());
            target.put("serverlessStructuralResolved", serverlessResolved.size());
            target.put("serverlessMissingCount", missingServerless.size());
            target.put("serverlessDiagnostic", serverlessDiagnostic);
            target.put("equipmentDirectResolved", directKeys.size() - missingDirect.size());
            target.put("equipmentTotalResolved", equipmentKeys.size() - missingEquipment.size());
            target.put("equipmentMissingCount", missingEquipment.size());
            targets.add(target);
        }

        if (profile.definitions().size() != catalog.expectations().registryTotal()) {
            mismatches.add("registry total=" + profile.definitions().size() + '/'
                    + catalog.expectations().registryTotal());
        }
        for (Map.Entry<String, Integer> expected
                : catalog.expectations().categories().entrySet()) {
            AnalysisProfile.Category category = AnalysisProfile.Category.valueOf(
                    expected.getKey().toUpperCase(java.util.Locale.ROOT));
            int actual = categoryKeys.getOrDefault(category, Set.of()).size();
            if (actual != expected.getValue()) {
                mismatches.add("category " + expected.getKey() + '=' + actual + '/'
                        + expected.getValue());
            }
        }
        if (targets.size() != catalog.fixtures().size()) {
            mismatches.add("validation target count=" + targets.size() + '/'
                    + catalog.fixtures().size());
        }

        Map<SymbolKind, Integer> kinds = new EnumMap<>(SymbolKind.class);
        Map<AnalysisProfile.Category, Integer> categories =
                new EnumMap<>(AnalysisProfile.Category.class);
        profile.definitions().values().forEach(value -> {
            kinds.merge(value.kind(), 1, Integer::sum);
            categories.merge(value.category(), 1, Integer::sum);
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", mismatches.isEmpty() ? "PASS" : "FAIL");
        report.put("resolutionMode", "STRUCTURAL_ONLY");
        report.put("minecraftVersion", profile.minecraftVersion());
        report.put("profileSha256", profile.profileSha256());
        report.put("registryDefinitionSha256", profile.registryDefinitionSha256());
        report.put("fingerprintDefinitionSha256", profile.fingerprintDefinitionSha256());
        report.put("registryTotal", profile.definitions().size());
        report.put("kinds", Map.of("class", kinds.getOrDefault(SymbolKind.CLASS, 0),
                "method", kinds.getOrDefault(SymbolKind.METHOD, 0),
                "field", kinds.getOrDefault(SymbolKind.FIELD, 0)));
        report.put("categories", Map.of(
                "serverless", categories.getOrDefault(
                        AnalysisProfile.Category.SERVERLESS, 0),
                "equipmentDirect", categories.getOrDefault(
                        AnalysisProfile.Category.EQUIPMENT_DIRECT, 0),
                "equipmentRelated", categories.getOrDefault(
                        AnalysisProfile.Category.EQUIPMENT_RELATED, 0)));
        report.put("validationTargets", targets.size());
        report.put("mismatches", mismatches);
        report.put("targets", targets);

        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(normalized, MappingToolMain.gson().toJson(report)
                + System.lineSeparator(), StandardCharsets.UTF_8);
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(String.join("; ", mismatches));
        }
    }

    private Map<AnalysisProfile.Category, Set<String>> categoryKeys() {
        Map<AnalysisProfile.Category, Set<String>> result =
                new EnumMap<>(AnalysisProfile.Category.class);
        profile.definitions().forEach((id, definition) ->
                result.computeIfAbsent(definition.category(), ignored -> new TreeSet<>()).add(id));
        return result;
    }

    private static Set<String> ids(Map<YsmSymbolKey<?>, ?> values) {
        Set<String> result = new TreeSet<>();
        values.keySet().forEach(key -> result.add(key.id()));
        return result;
    }

    private static Set<String> missing(Set<String> required, Set<String> actual) {
        Set<String> result = new TreeSet<>(required);
        result.removeAll(actual);
        return result;
    }
}
