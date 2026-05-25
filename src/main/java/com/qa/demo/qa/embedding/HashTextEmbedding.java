package com.qa.demo.qa.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地 hash 伪向量（无外部 API）；仅用于未配置百炼密钥时的降级。
 */
public final class HashTextEmbedding {

    private HashTextEmbedding() {
    }

    public static List<Double> embed(String text, int dim) throws Exception {
        double[] vec = new double[dim];
        for (String token : tokenize(text)) {
            byte[] digest = sha256(token);
            int idx = toInt(digest[0], digest[1], digest[2], digest[3]) % dim;
            if (idx < 0) {
                idx += dim;
            }
            double sign = (digest[4] & 0x01) == 0 ? 1.0 : -1.0;
            double weight = 1.0 + ((digest[5] & 0xff) / 255.0);
            vec[idx] += sign * weight;
        }
        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        List<Double> out = new ArrayList<>(dim);
        for (double v : vec) {
            out.add(norm > 0 ? v / norm : 0.0);
        }
        return out;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(ch) || ",.;:|()[]{}<>!?\"'，。；：、（）".indexOf(ch) >= 0) {
                flushBuffer(tokens, buffer);
                continue;
            }
            if (isCjk(ch)) {
                flushBuffer(tokens, buffer);
                tokens.add(String.valueOf(ch));
            } else {
                buffer.append(ch);
            }
        }
        flushBuffer(tokens, buffer);
        return tokens;
    }

    private static void flushBuffer(List<String> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }

    private static byte[] sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static int toInt(byte b0, byte b1, byte b2, byte b3) {
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }
}
