package com.knowledgehub.media;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.media.MediaService.ImageView;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/libraries/{libraryId}/images")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ImageView upload(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                     @RequestParam MultipartFile file) {
        if (file.getSize() > ImageFormat.MAX_BYTES) {
            throw ImageFormat.tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "无法读取上传的图片");
        }
        return media.upload(account.id(), libraryId, file.getContentType(), bytes);
    }

    @GetMapping("/{imageId}")
    ResponseEntity<InputStreamResource> get(@AuthenticationPrincipal CurrentAccount account,
                                            @PathVariable UUID libraryId, @PathVariable UUID imageId) {
        var image = media.open(account.id(), libraryId, imageId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(new InputStreamResource(image.body()));
    }
}
