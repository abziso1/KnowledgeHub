package com.knowledgehub.note;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.library.LibraryAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NoteSearch {

    private final JdbcTemplate jdbc;
    private final LibraryAccess access;

    public NoteSearch(JdbcTemplate jdbc, LibraryAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record SearchHit(UUID id, String title, List<String> folderPath, Instant updatedAt) {}

    Page<SearchHit> search(UUID accountId, UUID libraryId, String query, int page, int requestedSize) {
        access.requireActive(accountId, libraryId);
        if (query.isBlank() || query.indexOf(0) >= 0) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请输入搜索词");
        }
        int size = NoteService.pageSize(page, requestedSize);
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = jdbc.query("""
                with hits as (
                    select id, library_id, folder_id, title, updated_at from notes
                    where library_id = ? and trash_batch_id is null
                        and (title ilike ? escape '!' or markdown ilike ? escape '!')
                    order by updated_at desc, id limit ? offset ?
                )
                select h.id, h.title, h.updated_at, array(
                    with recursive ancestry(id, parent_id, name, depth) as (
                        select f.id, f.parent_id, f.name, 0 from folders f
                        where f.library_id = h.library_id and f.id = h.folder_id
                        union all
                        select f.id, f.parent_id, f.name, a.depth + 1
                        from folders f join ancestry a on f.id = a.parent_id
                        where f.library_id = h.library_id
                    )
                    select name from ancestry order by depth desc
                ) as folder_path
                from hits h order by h.updated_at desc, h.id
                """, (row, index) -> {
            var path = row.getArray("folder_path");
            try {
                return new SearchHit(row.getObject("id", UUID.class), row.getString("title"),
                        List.of((String[]) path.getArray()),
                        row.getObject("updated_at", OffsetDateTime.class).toInstant());
            } finally {
                path.free();
            }
        }, libraryId, pattern, pattern, size + 1, (long) page * size);
        return NoteService.slice(rows, page, size);
    }
}
