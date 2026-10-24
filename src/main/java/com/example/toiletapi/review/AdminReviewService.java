package com.example.toiletapi.review;

import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class AdminReviewService {
    private static final ZoneOffset KST=ZoneOffset.ofHours(9);
    private final AdminReviewRepository repository;
    public AdminReviewService(AdminReviewRepository repository) { this.repository=repository; }

    public AdminReviewModels.Page page(String cursor,int size) {
        if(size<1 || size>50)throw new IllegalArgumentException("목록 개수는 1~50으로 입력해 주세요.");
        var rows=repository.page(ReviewCursor.parse(cursor),size);
        boolean more=rows.size()>size;
        var visible=rows.subList(0,Math.min(rows.size(),size));
        String next=more?new ReviewCursor(visible.getLast().createdAt(),visible.getLast().id()).encode():null;
        return new AdminReviewModels.Page(visible.stream().map(AdminReviewService::item).toList(),next,more);
    }
    public AdminReviewModels.Detail detail(long id) {
        if(id<=0)throw new IllegalArgumentException("리뷰 번호를 확인해 주세요.");
        var row=repository.find(id).orElseThrow(()->new ReviewFailure(404,"REVIEW_NOT_FOUND","리뷰를 찾을 수 없습니다."));
        var item=item(row);
        return new AdminReviewModels.Detail(item.id(),item.toiletId(),item.toiletName(),item.toiletVisibility(),
                item.satisfaction(),item.cleanliness(),item.paper(),item.waitMinutes(),item.authorDisplayName(),
                item.authorRemoved(),item.createdAt(),item.updatedAt(),item.hasComment(),row.comment(),
                row.reviewKey(),row.version(),AdminReviewModerationService.POLICY_VERSION);
    }
    private static AdminReviewModels.Item item(AdminReviewRepository.Row row) {
        boolean removed=row.detached() || row.missingAuthor() || !"ACTIVE".equals(row.authorStatus());
        String name=row.detached()?"익명":row.missingAuthor() || "WITHDRAWN".equals(row.authorStatus())?"탈퇴한 사용자"
                :!"ACTIVE".equals(row.authorStatus())?"급똥 사용자"
                :row.displayName()==null || row.displayName().isBlank()?"급똥 사용자":row.displayName();
        return new AdminReviewModels.Item(Long.toString(row.id()),row.toiletId(),
                row.toiletName()==null?"확인할 수 없는 화장실":row.toiletName(),
                row.toiletVisibility()==null?"UNKNOWN":row.toiletVisibility(),row.satisfaction(),row.cleanliness(),
                row.paper(),row.waitMinutes(),name,removed,row.createdAt().atOffset(KST),row.updatedAt().atOffset(KST),row.hasComment());
    }
}
