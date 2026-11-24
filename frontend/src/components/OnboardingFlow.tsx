import React from 'react';
import { CodeStep, EmailStep, PhoneFrame, ProfileStep, type OnboardingSchool } from './onboarding/OnboardingSteps.tsx';
import { getSchoolDepartments, MY_SCHOOL_ID } from '../data/gameRules.ts';
import { REAL_UNIVERSITIES } from '../data/universitiesData.ts';
import { MOCK_AUTH_RULES } from '../utils/mockAuth.ts';

interface OnboardingFlowProps {
  /** Every button of the comparison opens the working sequential onboarding. */
  onStartSequential: () => void;
}

// Fixed sample values for the side-by-side design view. Nothing here changes the auth or game state.
const SAMPLE_EMAIL = 'student@hanyang.ac.kr';
const SAMPLE_BASE = REAL_UNIVERSITIES.find((school) => school.id === MY_SCHOOL_ID)!;
const SAMPLE_SCHOOL: OnboardingSchool = { name: SAMPLE_BASE.name, firstSyllable: SAMPLE_BASE.firstSyllable, badgeColor: SAMPLE_BASE.badgeColor };
const SAMPLE_DEPARTMENTS = getSchoolDepartments(MY_SCHOOL_ID);
const noop = () => {};

const StepLabel: React.FC<{ index: number; text: string }> = ({ index, text }) => (
  <div className="mb-2 flex items-center gap-1.5 font-pixel text-xs text-[#8b5327] font-bold">
    <span className="w-5 h-5 bg-[#261608] text-[#fef08a] flex items-center justify-center text-[10px] font-press-start">{index}</span>
    <span>{text}</span>
  </div>
);

/**
 * 3-screen comparison of the onboarding design (kept as a demo, M4-B). The same step components drive the
 * sequential onboarding; here they show fixed samples and every action opens the sequential flow.
 */
export const OnboardingFlow: React.FC<OnboardingFlowProps> = ({ onStartSequential }) => (
  <div className="w-full flex flex-col items-center select-none py-2">
    <p className="mb-3 font-pixel text-[11px] text-[#8b5327] text-center">
      시안 비교 화면(고정 샘플)입니다. 버튼을 누르면 실제로 진행되는 순차 온보딩으로 이동합니다.
    </p>
    <div className="w-full max-w-7xl flex flex-col lg:flex-row items-center justify-center gap-6 xl:gap-8 px-2 overflow-x-auto pb-6">
      <div className="flex flex-col items-center shrink-0">
        <StepLabel index={1} text="타이틀 및 학교 메일 입력" />
        <PhoneFrame>
          <EmailStep email={SAMPLE_EMAIL} onEmailChange={noop} onRequestCode={onStartSequential} />
        </PhoneFrame>
      </div>
      <div className="flex flex-col items-center shrink-0">
        <StepLabel index={2} text="6자리 인증 코드 입력" />
        <PhoneFrame>
          <CodeStep
            email={SAMPLE_EMAIL}
            school={SAMPLE_SCHOOL}
            remainingSeconds={MOCK_AUTH_RULES.codeTtlMs / 1000 - 1}
            attemptsLeft={MOCK_AUTH_RULES.maxAttempts}
            resendWaitSeconds={0}
            mailboxCode="724815"
            initialDigits={['7', '2', '4']}
            onVerify={onStartSequential}
            onResend={onStartSequential}
            onEditEmail={onStartSequential}
          />
        </PhoneFrame>
      </div>
      <div className="flex flex-col items-center shrink-0">
        <StepLabel index={3} text="학과 선택 & 학생증 발급" />
        <PhoneFrame>
          <ProfileStep
            school={SAMPLE_SCHOOL}
            email={SAMPLE_EMAIL}
            nickname="열정학번사자"
            onNicknameChange={noop}
            departments={SAMPLE_DEPARTMENTS.slice(0, 5)}
            departmentId={SAMPLE_DEPARTMENTS[0].id}
            onDepartmentChange={noop}
            progressChoice="keep"
            onEnroll={onStartSequential}
          />
        </PhoneFrame>
      </div>
    </div>
  </div>
);
