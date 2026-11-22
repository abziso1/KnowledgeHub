package com.knowledgehub.note;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.library.LibraryService;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public final class MarkdownImport {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private MarkdownImport() {}

    public record ImportedMarkdown(String title, String markdown) {}

    public static ImportedMarkdown decode(String filename, byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (filename == null) {
            throw invalidFile();
        }
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        if (!basename.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw invalidFile();
        }
        String title = LibraryService.requiredName(basename.substring(0, basename.length() - 3));
        try {
            String markdown = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (markdown.startsWith("\uFEFF")) {
                markdown = markdown.substring(1);
            }
            return new ImportedMarkdown(title, NoteService.requireMarkdown(markdown));
        } catch (CharacterCodingException exception) {
            throw invalidFile();
        }
    }

    static ApiProblem tooLarge() {
        return new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Markdown 文件超过 2 MiB");
    }

    private static ApiProblem invalidFile() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请选择 UTF-8 编码的 .md 文件");
    }
}
