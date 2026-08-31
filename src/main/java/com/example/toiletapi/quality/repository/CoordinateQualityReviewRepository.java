package com.example.toiletapi.quality.repository;

import com.example.toiletapi.quality.model.CoordinateQualityReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinateQualityReviewRepository extends JpaRepository<CoordinateQualityReview, String> {
}
