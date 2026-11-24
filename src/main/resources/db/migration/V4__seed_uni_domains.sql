-- 대대대: 학교별 허용 이메일 도메인 초기 데이터 (uni_domain)
-- 기준: 2026-11-13. 학교 목록은 V2__seed_universities.sql(학교 76개)을 따른다.
--
-- 주의: 도메인은 공식 출처로 확인하지 않았다 (작성 시점의 알려진 대표·학생 메일 도메인).
--       틀린 도메인은 그 학교 학생이 가입하지 못하게 할 뿐, 다른 학교로 잘못 배정하지는 않는다.
--       학교별로 확인한 뒤 새 마이그레이션으로 고친다. 이미 적용한 이 파일은 고치지 않는다.
-- 주의: 학교는 이름으로 찾는다. V2__seed_universities.sql이 먼저 적용돼야 한다.
--       university에 행이 있는데 아래 이름의 학교가 없으면 마이그레이션을 실패시킨다 (이름 오타·학교명 변경 감지).
--       university가 비어 있으면(학교 시드 적용 전) 아무 행도 넣지 않는다.
-- 강원대학교: 강릉원주대학교가 강원대학교로 통합되어(V2 주석) gwnu.ac.kr도 강원대학교로 둔다.

CREATE TEMPORARY TABLE uni_domain_seed (
    university_name  VARCHAR(100)  NOT NULL,
    domain           VARCHAR(100)  NOT NULL
);

INSERT INTO uni_domain_seed (university_name, domain) VALUES
    ('KAIST', 'kaist.ac.kr'),
    ('DGIST', 'dgist.ac.kr'),
    ('부산대학교', 'pusan.ac.kr'),
    ('전남대학교', 'jnu.ac.kr'),
    ('서울대학교', 'snu.ac.kr'),
    ('연세대학교', 'yonsei.ac.kr'),
    ('고려대학교', 'korea.ac.kr'),
    ('한양대학교', 'hanyang.ac.kr'),
    ('성균관대학교', 'skku.edu'),
    ('성균관대학교', 'g.skku.edu'),
    ('서강대학교', 'sogang.ac.kr'),
    ('중앙대학교', 'cau.ac.kr'),
    ('경희대학교', 'khu.ac.kr'),
    ('이화여자대학교', 'ewha.ac.kr'),
    ('이화여자대학교', 'ewhain.net'),
    ('한국외국어대학교', 'hufs.ac.kr'),
    ('국민대학교', 'kookmin.ac.kr'),
    ('인하대학교', 'inha.ac.kr'),
    ('인하대학교', 'inha.edu'),
    ('인천대학교', 'inu.ac.kr'),
    ('경인교육대학교', 'ginue.ac.kr'),
    ('인천가톨릭대학교', 'iccu.ac.kr'),
    ('아주대학교', 'ajou.ac.kr'),
    ('가천대학교', 'gachon.ac.kr'),
    ('한국항공대학교', 'kau.ac.kr'),
    ('한국항공대학교', 'kau.kr'),
    ('경기대학교', 'kyonggi.ac.kr'),
    ('대진대학교', 'daejin.ac.kr'),
    ('용인대학교', 'yongin.ac.kr'),
    ('강남대학교', 'kangnam.ac.kr'),
    ('수원대학교', 'suwon.ac.kr'),
    ('한신대학교', 'hs.ac.kr'),
    ('한경국립대학교', 'hknu.ac.kr'),
    ('강원대학교', 'kangwon.ac.kr'),
    ('강원대학교', 'gwnu.ac.kr'),
    ('한림대학교', 'hallym.ac.kr'),
    ('상지대학교', 'sangji.ac.kr'),
    ('가톨릭관동대학교', 'cku.ac.kr'),
    ('충북대학교', 'chungbuk.ac.kr'),
    ('충북대학교', 'cbnu.ac.kr'),
    ('한국교원대학교', 'knue.ac.kr'),
    ('국립한국교통대학교', 'ut.ac.kr'),
    ('청주대학교', 'cju.ac.kr'),
    ('서원대학교', 'seowon.ac.kr'),
    ('세명대학교', 'semyung.ac.kr'),
    ('청주교육대학교', 'cje.ac.kr'),
    ('중원대학교', 'jwu.ac.kr'),
    ('극동대학교', 'kdu.ac.kr'),
    ('유원대학교', 'u1.ac.kr'),
    ('충남대학교', 'cnu.ac.kr'),
    ('충남대학교', 'o.cnu.ac.kr'),
    ('국립한밭대학교', 'hanbat.ac.kr'),
    ('한남대학교', 'hnu.kr'),
    ('한국기술교육대학교', 'koreatech.ac.kr'),
    ('순천향대학교', 'sch.ac.kr'),
    ('국립공주대학교', 'kongju.ac.kr'),
    ('호서대학교', 'hoseo.ac.kr'),
    ('선문대학교', 'sunmoon.ac.kr'),
    ('백석대학교', 'bu.ac.kr'),
    ('남서울대학교', 'nsu.ac.kr'),
    ('건양대학교', 'konyang.ac.kr'),
    ('한서대학교', 'hanseo.ac.kr'),
    ('청운대학교', 'chungwoon.ac.kr'),
    ('전북대학교', 'jbnu.ac.kr'),
    ('조선대학교', 'chosun.ac.kr'),
    ('GIST', 'gist.ac.kr'),
    ('GIST', 'gm.gist.ac.kr'),
    ('원광대학교', 'wku.ac.kr'),
    ('전주대학교', 'jj.ac.kr'),
    ('국립군산대학교', 'kunsan.ac.kr'),
    ('국립순천대학교', 'scnu.ac.kr'),
    ('국립목포대학교', 'mokpo.ac.kr'),
    ('우석대학교', 'woosuk.ac.kr'),
    ('경북대학교', 'knu.ac.kr'),
    ('POSTECH', 'postech.ac.kr'),
    ('UNIST', 'unist.ac.kr'),
    ('계명대학교', 'kmu.ac.kr'),
    ('영남대학교', 'yu.ac.kr'),
    ('국립부경대학교', 'pknu.ac.kr'),
    ('동아대학교', 'donga.ac.kr'),
    ('국립창원대학교', 'changwon.ac.kr'),
    ('경상국립대학교', 'gnu.ac.kr'),
    ('제주대학교', 'jejunu.ac.kr'),
    ('제주국제대학교', 'jeju.ac.kr');

DO $$
DECLARE
    missing TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM university) THEN
        SELECT string_agg(DISTINCT s.university_name, ', ')
          INTO missing
          FROM uni_domain_seed s
         WHERE NOT EXISTS (SELECT 1 FROM university u WHERE u.name = s.university_name);

        IF missing IS NOT NULL THEN
            RAISE EXCEPTION 'uni_domain 시드의 학교가 university에 없습니다: %', missing;
        END IF;
    END IF;
END
$$;

INSERT INTO uni_domain (university_id, domain)
SELECT u.id, s.domain
  FROM uni_domain_seed s
  JOIN university u ON u.name = s.university_name;

DROP TABLE uni_domain_seed;
