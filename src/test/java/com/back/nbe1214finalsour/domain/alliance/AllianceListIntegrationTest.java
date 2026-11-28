package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceInvitationService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AllianceListIntegrationTest {
    private static final String ROOT = "/api/v1/alliances";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired EmailHasher hasher;
    @Autowired UserRepository users;
    @Autowired AllianceService alliances;
    @Autowired AllianceMatchService matches;
    @Autowired AllianceInvitationService invitations;
    @Autowired EntityManager em;

    @Test
    void recruitingDirectoryUsesStableIdCursorAndRequiresLogin() throws Exception {
        User viewer = member();
        long older = create(member(), "older");
        long newest = create(member(), "newest");
        long expired = create(member(), "expired");
        markExpired(expired);
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        read(viewer, "?recruiting=true&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(newest))
                .andExpect(jsonPath("$.data.items[0].memberCount").value(1))
                .andExpect(jsonPath("$.data.nextCursor").value(newest));
        create(member(), "inserted between pages");
        read(viewer, "?recruiting=true&size=1&cursor=" + newest).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(older));
        read(viewer, "?cursor=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.nextCursor").isEmpty());
        read(viewer, "?size=51").andExpect(status().isBadRequest());
        read(viewer, "?cursor=0").andExpect(status().isBadRequest());
        read(viewer, "?status=UNKNOWN").andExpect(status().isBadRequest());
        read(viewer, "/" + newest + "/universities").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].name").isString());
        read(viewer, "/" + expired + "/universities").andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        read(viewer, "/me").andExpect(status().isOk());
    }

    @Test
    void submissionsAreScopedToCurrentRecipientOrAuthenticatedSendingSchool() throws Exception {
        User founder = member();
        User applicant = member();
        User outsider = member();
        long allianceId = create(founder, "target");
        long id = alliances.applyToAlliance(applicant.getId(), allianceId).getId();
        String inbox = "/" + allianceId + "/applications";
        read(founder, inbox).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
        read(applicant, inbox).andExpect(status().isForbidden());
        read(applicant, "/applications/me?status=PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].alliance.id").value(allianceId));
        read(outsider, "/applications/me?universityId=" + applicant.getUniversityId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        alliances.approveApplication(founder.getId(), id);
        em.flush();
        read(applicant, "/applications/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("ACCEPTED"));
        read(founder, "/applications/me").andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        markExpired(allianceId);
        read(founder, inbox).andExpect(status().isForbidden());
    }

    @Test
    void detailAndMatchListApplySameCurrentAndHistoricalSchoolPolicy() throws Exception {
        User a = member();
        User b = member();
        User outsider = member();
        long aId = create(a, "alliance A");
        long bId = create(b, "alliance B");
        join(a, member(), aId);
        join(b, member(), bId);
        long id = matches.requestMatch(a.getId(), aId, bId, 6).getId();
        String detail = "/matches/" + id;
        String list = "/" + aId + "/matches";
        read(a, list + "?direction=OUTGOING").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].opponent.id").value(bId))
                .andExpect(jsonPath("$.data.items[0].myScore").isEmpty());
        read(b, "/" + bId + "/matches?direction=INCOMING").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id));
        read(a, list + "?direction=INCOMING").andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        read(outsider, list).andExpect(status().isForbidden());
        read(outsider, detail).andExpect(status().isForbidden());
        matches.acceptMatch(b.getId(), id);
        em.flush();
        jdbc.update("UPDATE alliance_match SET status='FINISHED', a_score=0, b_score=2, winner_alliance_id=? WHERE id=?", bId, id);
        markExpired(aId);
        jdbc.update("UPDATE alliance_university SET status='LEFT' WHERE alliance_id=? AND status='ACCEPTED'", aId);
        em.clear(); // Fixture SQL bypassed JPA; HTTP reads must see the finalized row.
        read(a, list).andExpect(status().isForbidden());
        read(a, list + "?status=FINISHED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].myScore").value(0))
                .andExpect(jsonPath("$.data.items[0].result").value("LOSE"));
        read(a, "?scope=MEMBERSHIP_HISTORY").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(aId));
        read(a, detail).andExpect(status().isOk());
        read(outsider, list + "?status=FINISHED").andExpect(status().isForbidden());
        jdbc.update("UPDATE alliance SET deleted_at=now() WHERE id=?", aId);
        em.clear();
        read(a, list + "?status=FINISHED").andExpect(status().isNotFound());
        read(a, detail).andExpect(status().isForbidden());
    }

    @Test
    void invitationHttpFlowKeepsRequestsSeparatedAndChecksSchoolPermissions() throws Exception {
        User founder = member();
        User recipient = member();
        User outsider = member();
        long allianceId = create(founder, "초대 HTTP");
        String url = ROOT + "/" + allianceId + "/invitations";
        String body = "{\"universityId\":" + recipient.getUniversityId() + "}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("Authorization", token(outsider)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", token(founder)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"));
        em.flush();
        Long id = jdbc.queryForObject("SELECT id FROM alliance_university WHERE alliance_id=? AND university_id=? AND origin='INVITATION'", Long.class, allianceId, recipient.getUniversityId());
        read(recipient, "/invitations/me").andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
        read(founder, "/" + allianceId + "/invitations").andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));
        read(outsider, "/" + allianceId + "/invitations").andExpect(status().isForbidden());
        read(recipient, "/applications/me").andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        read(founder, "/" + allianceId + "/applications").andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        read(recipient, "/invitations/me?size=0").andExpect(status().isBadRequest());
        mvc.perform(patch(ROOT + "/invitations/" + id + "/approve").header("Authorization", token(outsider))).andExpect(status().isForbidden());
        mvc.perform(patch(ROOT + "/applications/" + id + "/approve").header("Authorization", token(founder))).andExpect(status().isNotFound());
        mvc.perform(patch(ROOT + "/invitations/" + id + "/approve").header("Authorization", token(recipient)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    void universityAllianceHttpReportsMembershipAndCapacity() throws Exception {
        User founder = member();
        User applicant = member();
        String schoolUrl = "/api/v1/universities/" + founder.getUniversityId() + "/alliance";
        mvc.perform(get(schoolUrl).header("Authorization", token(applicant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        long id = create(founder, "학교 연합 조회");
        mvc.perform(get(schoolUrl).header("Authorization", token(applicant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.joinable").value(true));
        join(founder, applicant, id);
        em.flush();
        mvc.perform(get(schoolUrl).header("Authorization", token(applicant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.joinable").value(false));
        mvc.perform(get("/api/v1/universities/999999999/alliance").header("Authorization", token(applicant)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitationInboxPaginatesAndShowsWhyAStillPendingInvitationCannotBeApproved() throws Exception {
        User firstFounder = member();
        User secondFounder = member();
        User recipient = member();
        long first = create(firstFounder, "받은 초대 1");
        long second = create(secondFounder, "받은 초대 2");
        long older = invitations.invite(firstFounder.getId(), first, recipient.getUniversityId()).getId();
        long newer = invitations.invite(secondFounder.getId(), second, recipient.getUniversityId()).getId();
        em.flush();

        read(recipient, "/invitations/me?status=PENDING&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(newer))
                .andExpect(jsonPath("$.data.items[0].approvalBlockReason").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(newer));
        read(recipient, "/invitations/me?size=1&cursor=" + newer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(older))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        read(recipient, "/invitations/me?cursor=0").andExpect(status().isBadRequest());
        read(firstFounder, "/invitations/me?universityId=" + recipient.getUniversityId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        invitations.approve(recipient.getId(), newer);
        em.flush();
        read(recipient, "/invitations/me?status=PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(older))
                .andExpect(jsonPath("$.data.items[0].approvalBlockReason").value("UNIVERSITY_ALREADY_JOINED"));
        mvc.perform(patch(ROOT + "/invitations/" + older + "/approve").header("Authorization", token(recipient)))
                .andExpect(status().isConflict());
        mvc.perform(patch(ROOT + "/invitations/" + older + "/reject").header("Authorization", token(firstFounder)))
                .andExpect(status().isForbidden());
        mvc.perform(patch(ROOT + "/invitations/" + older + "/reject").header("Authorization", token(recipient)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        em.flush();
        read(recipient, "/invitations/me?status=REJECTED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].approvalBlockReason").value("ALREADY_PROCESSED"));
    }

    private String token(User user) {
        return "Bearer " + jwt.createAccessToken(new AuthUser(user.getId(), user.getUniversityId(), null, Role.USER), null);
    }

    private void join(User approver, User applicant, long allianceId) {
        alliances.approveApplication(approver.getId(), alliances.applyToAlliance(applicant.getId(), allianceId).getId());
    }

    private long create(User owner, String name) {
        return alliances.createAlliance(owner.getId(), name, 48).getId();
    }

    private void markExpired(long allianceId) {
        jdbc.update("UPDATE alliance SET created_at=now()-interval '2 days', expired_at=now()-interval '1 day' WHERE id=?", allianceId);
    }

    private ResultActions read(User viewer, String path) throws Exception {
        String token = jwt.createAccessToken(new AuthUser(viewer.getId(), viewer.getUniversityId(), null, Role.USER), null);
        return mvc.perform(get(ROOT + path).header("Authorization", "Bearer " + token));
    }

    private User member() {
        String key = UUID.randomUUID().toString();
        long school = jdbc.queryForObject("INSERT INTO university(name) VALUES(?) RETURNING id", Long.class, "목록 " + key);
        long dept = jdbc.queryForObject("INSERT INTO department(university_id,name) VALUES(?, '학과') RETURNING id", Long.class, school);
        String email = "list-" + key + "@test.ac.kr";
        return users.saveAndFlush(User.create(school, dept, "목록" + key.substring(0, 8), email, hasher.hash(email),
                "{noop}unused", OffsetDateTime.now()));
    }
}
