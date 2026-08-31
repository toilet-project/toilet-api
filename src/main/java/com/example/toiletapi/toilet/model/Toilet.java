package com.example.toiletapi.toilet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

    public void applyAdminConfirmedCoordinates(BigDecimal latitude, BigDecimal longitude, String roadAddress) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.roadAddress = roadAddress;
        this.coordinateSource = "ADMIN_CONFIRMED";
    }

    public void applyReportedOpenTime(String openTime) {
        this.openTime = openTime;
    }
}
