// 임시 목업 값이며 실제 가격, 결제 수단 또는 서버 정책이 아니다.
export interface MockDurationHoursConfig {
  min: number;
  max: number;
  default: number;
  /** 기간 선택 UI의 빠른 선택 값(시간). min~max 범위 밖 값은 UI에서 무시한다. */
  presets: readonly number[];
}

export interface MockBattleConfig {
  durationHours: MockDurationHoursConfig;
  startTime: { minLeadMinutes: number; maxLeadDays: number; allowedStartHoursKst: { from: number; to: number }; stepMinutes: number };
}

export interface MockAllianceConfig {
  durationHours: MockDurationHoursConfig;
}

export interface MockShopConfig {
  coffee: { price: number; currency: 'contribution'; durationMs: number; multiplier: number };
  imagePricesKrw: { school: number; department: number; profile: number };
  alliance: MockAllianceConfig;
  battle: MockBattleConfig;
}

export const MOCK_SHOP_CONFIG: MockShopConfig = {
  coffee: { price: 120, currency: 'contribution', durationMs: 30_000, multiplier: 2 },
  imagePricesKrw: { school: 500, department: 500, profile: 500 },
  alliance: {
    // 연합 기간 6~72시간, 수락(성립) 시점부터 계산: ERD 결정 10. 기본값·프리셋은 목업 값.
    durationHours: { min: 6, max: 72, default: 24, presets: [6, 12, 24, 48, 72] },
  },
  battle: {
    // 대결 기간 1~24시간: ERD 결정 10 (.agents/261111-1356-project_plan_reference.md). 기본값·프리셋은 목업 값.
    durationHours: { min: 1, max: 24, default: 3, presets: [1, 3, 6, 12, 24] },
    startTime: { minLeadMinutes: 60, maxLeadDays: 7, allowedStartHoursKst: { from: 9, to: 22 }, stepMinutes: 30 }, // 임시 목업 값
  },
};
