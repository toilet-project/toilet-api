package com.example.toiletapi.toilet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 화장실 위치와 기본 정보를 표현하는 엔티티입니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "toilet")
public class Toilet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "toilet_id")
    private Long id;

    @Column(name = "visibility_status", nullable = false)
    private String visibilityStatus = "VISIBLE";

    public boolean isPubliclyVisible() { return "VISIBLE".equals(visibilityStatus); }

    @Column(name = "mng_no", length = 50)
    private String managementNumber;

    @Column(length = 100)
    private String name;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "coordinate_source", length = 30)
    private String coordinateSource;

    @Column(name = "toilet_type", length = 20)
    private String toiletType;

    @Column(name = "road_address", length = 255)
    private String roadAddress;

    @Column(name = "jibun_address", length = 255)
    private String jibunAddress;

    @Column(name = "male_toilet_count")
    private Integer maleToiletCount;

    @Column(name = "male_urinal_count")
    private Integer maleUrinalCount;

    @Column(name = "male_disabled_toilet_count")
    private Integer maleDisabledToiletCount;

    @Column(name = "male_disabled_urinal_count")
    private Integer maleDisabledUrinalCount;

    @Column(name = "male_child_toilet_count")
    private Integer maleChildToiletCount;

    @Column(name = "male_child_urinal_count")
    private Integer maleChildUrinalCount;

    @Column(name = "female_toilet_count")
    private Integer femaleToiletCount;

    @Column(name = "female_disabled_toilet_count")
    private Integer femaleDisabledToiletCount;

    @Column(name = "female_child_toilet_count")
    private Integer femaleChildToiletCount;

    @Column(name = "agency_name", length = 100)
    private String agencyName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "open_time", length = 50)
    private String openTime;

    @Column(name = "open_time_detail", length = 255)
    private String openTimeDetail;

    @Column(name = "installation_date", length = 20)
    private String installationDate;

    @Column(name = "has_emergency_bell", length = 10)
    private String hasEmergencyBell;

    @Column(name = "emergency_bell_location", length = 100)
    private String emergencyBellLocation;

    @Column(name = "has_cctv", length = 10)
    private String hasCctv;

    @Column(name = "has_diaper_table", length = 10)
    private String hasDiaperTable;

    @Column(name = "diaper_table_location", length = 100)
    private String diaperTableLocation;

    @Column(name = "data_base_date", length = 20)
    private String dataBaseDate;

    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "region_revision", nullable = false)
    private Long regionRevision = 1L;

    public void applyAdminConfirmedCoordinates(BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress) {
        if (!sameCoordinate(this.latitude, latitude) || !sameCoordinate(this.longitude, longitude)
                || !Objects.equals(this.roadAddress, roadAddress) || !Objects.equals(this.jibunAddress, jibunAddress)) {
            this.regionRevision = (this.regionRevision == null ? 1L : this.regionRevision) + 1L;
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.roadAddress = roadAddress;
        this.jibunAddress = jibunAddress;
        this.coordinateSource = "ADMIN_CONFIRMED";
    }

    private static boolean sameCoordinate(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    public void applyReportedOpenTime(String openTime) {
        this.openTime = openTime;
    }

    /** 관리자 편집 화면에서 검증을 마친 값을 한 번에 반영합니다. */
    public void applyAdminUpdate(ToiletEditableData data) {
        if (!sameCoordinate(this.latitude, data.latitude())
                || !sameCoordinate(this.longitude, data.longitude())
                || !Objects.equals(this.roadAddress, data.roadAddress())
                || !Objects.equals(this.jibunAddress, data.jibunAddress())) {
            applyAdminConfirmedCoordinates(data.latitude(), data.longitude(), data.roadAddress(), data.jibunAddress());
        }
        this.name = data.name();
        this.toiletType = data.toiletType();
        this.maleToiletCount = data.maleToiletCount();
        this.maleUrinalCount = data.maleUrinalCount();
        this.maleDisabledToiletCount = data.maleDisabledToiletCount();
        this.maleDisabledUrinalCount = data.maleDisabledUrinalCount();
        this.maleChildToiletCount = data.maleChildToiletCount();
        this.maleChildUrinalCount = data.maleChildUrinalCount();
        this.femaleToiletCount = data.femaleToiletCount();
        this.femaleDisabledToiletCount = data.femaleDisabledToiletCount();
        this.femaleChildToiletCount = data.femaleChildToiletCount();
        this.agencyName = data.agencyName();
        this.phoneNumber = data.phoneNumber();
        this.openTime = data.openTime();
        this.openTimeDetail = data.openTimeDetail();
        this.installationDate = data.installationDate();
        this.hasEmergencyBell = data.hasEmergencyBell();
        this.emergencyBellLocation = data.emergencyBellLocation();
        this.hasCctv = data.hasCctv();
        this.hasDiaperTable = data.hasDiaperTable();
        this.diaperTableLocation = data.diaperTableLocation();
    }
}
