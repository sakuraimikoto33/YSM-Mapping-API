package net.okitsu.ysmmapping.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.okitsu.ysmmapping.api.YsmClassSymbol;
import net.okitsu.ysmmapping.api.YsmFieldSymbol;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmResolvedSymbol;
import net.okitsu.ysmmapping.internal.analysis.AnalysisProfile;
import net.okitsu.ysmmapping.internal.analysis.EquipmentSemanticAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.FixtureCatalog;
import net.okitsu.ysmmapping.internal.analysis.JarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.SymbolMappings;
import net.okitsu.ysmmapping.internal.analysis.WholeJarStructureAnalyzer;
import net.okitsu.ysmmapping.internal.analysis.YsmArtifact;
import net.okitsu.ysmmapping.internal.bootstrap.ContentHashes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Development-only profile-driven analyzer. Runtime never downloads YSM releases. */
public final class MappingToolMain {
    private MappingToolMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException(usage());
        switch (args[0]) {
            case "graph" -> graph(args);
            case "analyze" -> analyze(args);
            case "name-report" -> nameReport(args);
            case "equipment-report" -> equipmentReport(args);
            case "registry-report" -> registryReport(args);
            default -> throw new IllegalArgumentException(usage());
        }
    }

    private static void registryReport(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: registry-report <profile-json> "
                    + "<catalog-json> <official-jar-dir> <output-json>");
        }
        AnalysisProfile profile = AnalysisProfile.load(Path.of(args[1]));
        FixtureCatalog catalog = FixtureCatalog.load(Path.of(args[2]), profile);
        new SemanticRegistryReportGenerator(profile, catalog)
                .generate(Path.of(args[3]), Path.of(args[4]));
    }

    private static void equipmentReport(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: equipment-report <profile-json> <loader> <ysm-jar>");
        }
        AnalysisProfile profile = AnalysisProfile.load(Path.of(args[1]));
        Map<String, Object> entries = new TreeMap<>();
        new EquipmentSemanticAnalyzer().analyze(Path.of(args[3]), profile, args[2])
                .forEach((key, value) -> entries.put(key.id(), json(value)));
        System.out.println(gson().toJson(entries));
    }

    private static void graph(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: graph <ysm-jar> <output-json>");
        }
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, gson().toJson(new WholeJarStructureAnalyzer()
                .analyze(Path.of(args[1]).toAbsolutePath().normalize()))
                + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void nameReport(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("Usage: name-report <profile-json> "
                    + "<candidate-spec-json> <openysm-root> <port-root> "
                    + "<official-jar-dir> <output-json>");
        }
        AnalysisProfile profile = AnalysisProfile.load(Path.of(args[1]));
        new NameCandidateReportGenerator(profile).generate(Path.of(args[2]),
                Path.of(args[3]), Path.of(args[4]), Path.of(args[5]), Path.of(args[6]));
    }

    private static void analyze(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: analyze <profile-json> <loader> <ysm-version> <ysm-jar>");
        }
        AnalysisProfile profile = AnalysisProfile.load(Path.of(args[1]));
        profile.loader(args[2]);
        Path source = Path.of(args[4]).toAbsolutePath().normalize();
        String contentSha = ContentHashes.ysmClassesSha512(source);
        YsmArtifact artifact = new YsmArtifact("development", profile.minecraftVersion(),
                args[2], args[3], contentSha);
        var analyzed = new JarStructureAnalyzer(profile).analyzePartial(
                artifact, net.okitsu.ysmmapping.internal.analysis.YsmClassIndex.read(source));
        Map<String, Object> entries = new TreeMap<>();
        analyzed.symbols().forEach((key, value) -> entries.put(key.id(), json(value)));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("minecraftVersion", profile.minecraftVersion());
        output.put("loader", args[2]);
        output.put("ysmVersion", args[3]);
        output.put("contentSha512", contentSha);
        output.put("profileSha256", profile.profileSha256());
        output.put("registryDefinitionSha256", profile.registryDefinitionSha256());
        output.put("entries", entries);
        output.put("diagnosticCount", analyzed.diagnostics().size());
        System.out.println(gson().toJson(output));
    }

    private static String usage() {
        return "Usage: graph|analyze|equipment-report|registry-report|name-report ...";
    }

    static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    static Object json(YsmResolvedSymbol symbol) {
        if (symbol instanceof YsmClassSymbol value) {
            return Map.of("kind", "CLASS", "internalName", value.internalName());
        }
        if (symbol instanceof YsmMethodSymbol value) {
            return Map.of("kind", "METHOD", "owner", value.owner(), "name", value.name(),
                    "descriptor", value.descriptor());
        }
        YsmFieldSymbol value = (YsmFieldSymbol) symbol;
        return Map.of("kind", "FIELD", "owner", value.owner(), "name", value.name(),
                "descriptor", value.descriptor());
    }
}
