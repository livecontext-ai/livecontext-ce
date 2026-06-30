package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiSlugService {

    private final ApiRepository apiRepository;

    public String generateUniqueSlug(String baseSlug, String userId) {
        try {
            List<ApiEntity> userApis = apiRepository.findByCreatedBy(userId);
            List<String> existingSlugs = userApis.stream()
                    .map(ApiEntity::getApiSlug)
                    .filter(slug -> slug != null && !slug.isEmpty())
                    .collect(Collectors.toList());
            return SlugUtils.generateUniqueSlug(baseSlug, existingSlugs);
        } catch (Exception e) {
            log.warn("Error generating unique API slug, using base slug: {}", baseSlug, e);
            return baseSlug;
        }
    }
}
