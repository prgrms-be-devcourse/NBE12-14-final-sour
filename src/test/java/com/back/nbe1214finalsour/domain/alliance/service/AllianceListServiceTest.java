package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceListReader;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceListServiceTest {
    private static final long ALLIANCE_ID = 10L;
    private static final long MATCH_ID = 99L;
    private static final long SCHOOL_ID = 101L;
    private static final long OTHER_ALLIANCE_ID = 20L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-24T09:00:00+09:00");

    @Mock private AllianceListReader reader;
    @Mock private AllianceQueryService alliances;
    @Mock private AllianceMatchService matches;

    private AllianceListService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        service = new AllianceListService(reader, alliances, matches, clock);
    }

    @Test
    @DisplayName("연합 목록 조회 시 조건, 현재 시각, 커서와 페이지 크기를 전달한다")
    void directory_forwardsFiltersAndClock() {
        Page<Summary> page = new Page<>(
                List.of(new Summary(10L, "test", AllianceStatus.ACTIVE, NOW.plusDays(1), 1)), 10L);
        when(reader.directory(SCHOOL_ID, Scope.MEMBERSHIP_HISTORY, AllianceStatus.ACTIVE,
                true, NOW, 50L, 5)).thenReturn(page);

        assertThat(service.directory(SCHOOL_ID, Scope.MEMBERSHIP_HISTORY,
                AllianceStatus.ACTIVE, true, 50L, 5)).isSameAs(page);
        verify(reader).directory(SCHOOL_ID, Scope.MEMBERSHIP_HISTORY, AllianceStatus.ACTIVE,
                true, NOW, 50L, 5);
    }

    @Test
    @DisplayName("조건이 없는 공개 연합 목록도 조회 서비스로 전달한다")
    void directory_allowsNullableFilters() {
        service.directory(null, Scope.DIRECTORY, null, false, null, 20);

        verify(reader).directory(null, Scope.DIRECTORY, null, false, NOW, null, 20);
    }

    @Test
    @DisplayName("연합 소속 학교 조회 전 연합 존재 여부를 확인한다")
    void schools_checksAllianceAndReturnsSchools() {
        List<School> result = List.of(new School(SCHOOL_ID, "학교", null));
        when(reader.schools(ALLIANCE_ID, NOW)).thenReturn(result);

        assertThat(service.schools(ALLIANCE_ID)).isSameAs(result);
        InOrder order = inOrder(alliances, reader);
        order.verify(alliances).getAlliance(ALLIANCE_ID);
        order.verify(reader).schools(ALLIANCE_ID, NOW);
    }

    @Test
    @DisplayName("학교 조회 시 연합이 없으면 목록 저장소에 접근하지 않는다")
    void schools_missingAllianceStops() {
        when(alliances.getAlliance(ALLIANCE_ID)).thenThrow(new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        assertThatThrownBy(() -> service.schools(ALLIANCE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_FOUND));
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("받은 가입 신청은 학교의 현재 접근 권한을 확인하고 연합 기준으로 조회한다")
    void received_authorizedQueriesAllianceApplications() {
        Page<Application> page = new Page<>(List.of(), null);
        when(reader.canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false)).thenReturn(true);
        when(reader.applications(ALLIANCE_ID, null, AllianceUniversityStatus.PENDING, 70L, 10))
                .thenReturn(page);

        assertThat(service.received(ALLIANCE_ID, SCHOOL_ID,
                AllianceUniversityStatus.PENDING, 70L, 10)).isSameAs(page);
        verify(alliances).getAlliance(ALLIANCE_ID);
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        verify(reader).applications(ALLIANCE_ID, null, AllianceUniversityStatus.PENDING, 70L, 10);
    }

    @Test
    @DisplayName("받은 가입 신청은 접근 권한이 없으면 조회하지 않는다")
    void received_denied() {
        assertThatThrownBy(() -> service.received(ALLIANCE_ID, SCHOOL_ID, null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_MEMBER));

        verify(alliances).getAlliance(ALLIANCE_ID);
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        verify(reader, never()).applications(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("받은 가입 신청은 연합이 없으면 권한 조회를 시작하지 않는다")
    void received_missingAllianceStops() {
        when(alliances.getAlliance(ALLIANCE_ID)).thenThrow(new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        assertThatThrownBy(() -> service.received(ALLIANCE_ID, SCHOOL_ID, null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_FOUND));
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("보낸 가입 신청은 학교 기준으로 조회한다")
    void sent_usesSchoolFilter() {
        Page<Application> page = new Page<>(List.of(), null);
        when(reader.applications(null, SCHOOL_ID, AllianceUniversityStatus.ACCEPTED, 40L, 10))
                .thenReturn(page);

        assertThat(service.sent(SCHOOL_ID, AllianceUniversityStatus.ACCEPTED, 40L, 10)).isSameAs(page);
        verifyNoInteractions(alliances, matches);
    }

    @Test
    @DisplayName("일반 대결 목록은 현재 멤버십 권한으로 조회한다")
    void matches_nonFinishedChecksCurrentMembership() {
        Page<Match> page = new Page<>(List.of(), null);
        when(reader.canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false)).thenReturn(true);
        when(reader.matches(ALLIANCE_ID, Direction.INCOMING, AllianceMatchStatus.REQUESTED, 77L, 15))
                .thenReturn(page);

        assertThat(service.matches(ALLIANCE_ID, SCHOOL_ID, Direction.INCOMING,
                AllianceMatchStatus.REQUESTED, 77L, 15)).isSameAs(page);
        verify(alliances).getAlliance(ALLIANCE_ID);
        verify(reader).matches(ALLIANCE_ID, Direction.INCOMING, AllianceMatchStatus.REQUESTED, 77L, 15);
    }

    @Test
    @DisplayName("완료된 대결 목록은 과거 멤버십 권한으로 조회한다")
    void matches_finishedChecksHistoricalMembership() {
        when(reader.canRead(ALLIANCE_ID, SCHOOL_ID, NOW, true)).thenReturn(true);

        service.matches(ALLIANCE_ID, SCHOOL_ID, null, AllianceMatchStatus.FINISHED, null, 20);

        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, true);
        verify(reader).matches(ALLIANCE_ID, null, AllianceMatchStatus.FINISHED, null, 20);
    }

    @Test
    @DisplayName("대결 목록 조회 시 학교 권한이 없으면 조회하지 않는다")
    void matches_denied() {
        assertThatThrownBy(() -> service.matches(ALLIANCE_ID, SCHOOL_ID, null, null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_MEMBER));
        verify(reader, never()).matches(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("대결 상세 조회 시 삭제된 대결은 없는 대결로 처리한다")
    void match_deletedIsNotFound() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(matches.getMatch(MATCH_ID)).thenReturn(match);
        when(match.getDeletedAt()).thenReturn(NOW.minusMinutes(1));

        assertThatThrownBy(() -> service.match(MATCH_ID, SCHOOL_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATCH_NOT_FOUND));
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("대결 상세 조회 시 첫 번째 연합에 접근할 수 있으면 두 번째 연합은 검사하지 않는다")
    void match_firstAllianceAllowedShortCircuits() {
        AllianceMatch match = matching(AllianceMatchStatus.IN_PROGRESS);
        when(reader.canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false)).thenReturn(true);

        assertThat(service.match(MATCH_ID, SCHOOL_ID)).isSameAs(match);
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        verify(reader, never()).canRead(eq(OTHER_ALLIANCE_ID), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("첫 번째 연합은 불가해도 두 번째 연합 소속이면 대결 상세를 볼 수 있다")
    void match_secondAllianceAllowed() {
        AllianceMatch match = matching(AllianceMatchStatus.REQUESTED);
        when(match.getAllianceBId()).thenReturn(OTHER_ALLIANCE_ID);
        doReturn(false).when(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        doReturn(true).when(reader).canRead(OTHER_ALLIANCE_ID, SCHOOL_ID, NOW, false);

        assertThat(service.match(MATCH_ID, SCHOOL_ID)).isSameAs(match);
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        verify(reader).canRead(OTHER_ALLIANCE_ID, SCHOOL_ID, NOW, false);
    }

    @Test
    @DisplayName("어느 연합에도 접근할 수 없으면 대결 상세를 거부한다")
    void match_bothAlliancesDenied() {
        AllianceMatch match = matching(AllianceMatchStatus.REQUESTED);
        when(match.getAllianceBId()).thenReturn(OTHER_ALLIANCE_ID);

        assertThatThrownBy(() -> service.match(MATCH_ID, SCHOOL_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_MEMBER));
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, false);
        verify(reader).canRead(OTHER_ALLIANCE_ID, SCHOOL_ID, NOW, false);
    }

    @Test
    @DisplayName("완료된 대결 상세는 과거 멤버십을 인정한다")
    void match_finishedChecksHistoricalMembership() {
        AllianceMatch match = matching(AllianceMatchStatus.FINISHED);
        when(reader.canRead(ALLIANCE_ID, SCHOOL_ID, NOW, true)).thenReturn(true);

        assertThat(service.match(MATCH_ID, SCHOOL_ID)).isSameAs(match);
        verify(reader).canRead(ALLIANCE_ID, SCHOOL_ID, NOW, true);
    }

    private AllianceMatch matching(AllianceMatchStatus status) {
        AllianceMatch match = mock(AllianceMatch.class);
        when(matches.getMatch(MATCH_ID)).thenReturn(match);
        when(match.getStatus()).thenReturn(status);
        when(match.getAllianceAId()).thenReturn(ALLIANCE_ID);
        return match;
    }
}
