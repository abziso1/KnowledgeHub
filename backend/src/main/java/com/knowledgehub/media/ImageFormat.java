package com.knowledgehub.media;

import com.knowledgehub.common.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

enum ImageFormat {

    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp");

    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    String extension() {
        return extension;
    }

    String contentType() {
        return contentType;
    }

    static ImageFormat identify(byte[] data, String declaration) {
        if (data.length > MAX_BYTES) {
            throw tooLarge();
        }
        ImageFormat actual = detect(data);
        if (declaration != null && !declaration.isBlank()) {
            MediaType type;
            try {
                type = MediaType.parseMediaType(declaration);
            } catch (InvalidMediaTypeException exception) {
                throw invalid();
            }
            String declared = type.getType() + "/" + type.getSubtype();
            if (!declared.equals("application/octet-stream") && !declared.equals(actual.contentType)) {
                throw invalid();
            }
        }
        return actual;
    }

    static ApiProblem tooLarge() {
        return new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "图片超过 10 MiB");
    }

    private static ImageFormat detect(byte[] data) {
        if (data.length >= 33 && matches(data, 0, 0x89, 'P', 'N', 'G', 13, 10, 26, 10)
                && matches(data, 8, 0, 0, 0, 13, 'I', 'H', 'D', 'R')) {
            return PNG;
        }
        if (data.length >= 4 && matches(data, 0, 0xff, 0xd8, 0xff)) {
            return JPEG;
        }
        if (data.length >= 14 && (matches(data, 0, 'G', 'I', 'F', '8', '7', 'a')
                || matches(data, 0, 'G', 'I', 'F', '8', '9', 'a'))) {
            return GIF;
        }
        if (data.length >= 20 && matches(data, 0, 'R', 'I', 'F', 'F')
                && matches(data, 8, 'W', 'E', 'B', 'P', 'V', 'P', '8')
                && (data[15] == 'L' || data[15] == 'X' || data[15] == ' ')) {
            return WEBP;
        }
        throw invalid();
    }

    private static boolean matches(byte[] data, int start, int... signature) {
        for (int offset = 0; offset < signature.length; offset++) {
            if (Byte.toUnsignedInt(data[start + offset]) != signature[offset]) {
                return false;
            }
        }
        return true;
    }

    private static ApiProblem invalid() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "图片格式无效或与声明类型不符");
    }
}
