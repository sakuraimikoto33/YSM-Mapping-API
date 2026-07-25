package net.okitsu.ysmmapping.api;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical hashing and validation shared by definitions and cache. */
public final class YsmSymbolSignatures {
    private YsmSymbolSignatures() {
    }

    public static String sha256(String canonicalDefinition) {
        Objects.requireNonNull(canonicalDefinition, "canonicalDefinition");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalDefinition.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void requireBinaryName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.indexOf('/') >= 0 || value.startsWith(".")
                || value.endsWith(".")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        for (String part : value.split("\\.", -1)) {
            requireJavaIdentifier(part, label);
        }
    }

    static void requireMethodName(String value) {
        if ("<init>".equals(value)) {
            return;
        }
        if ("<clinit>".equals(value)) {
            throw new IllegalArgumentException("Class initializers are structural-only symbols");
        }
        requireJavaIdentifier(value, "method name");
    }

    static void requireJavaIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.codePointAt(0))) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        for (int offset = Character.charCount(value.codePointAt(0)); offset < value.length();) {
            int point = value.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(point)) {
                throw new IllegalArgumentException("Invalid " + label + ": " + value);
            }
            offset += Character.charCount(point);
        }
    }

    static void requireMethodDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            MethodTypeDesc.ofDescriptor(descriptor);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid method descriptor: " + descriptor,
                    exception);
        }
    }

    static void requireFieldDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if ("V".equals(descriptor)) {
            throw new IllegalArgumentException("A field cannot have the void descriptor");
        }
        try {
            ClassDesc.ofDescriptor(descriptor);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid field descriptor: " + descriptor,
                    exception);
        }
    }
}
