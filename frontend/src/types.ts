export interface FloatingNumber {
  id: string;
  x: number;
  y: number;
  text: string;
  isCrit: boolean;
  isTeam?: boolean;
  drift: number;
}

export interface Sparkle {
  id: string;
  x: number;
  y: number;
  size: number;
  color: string;
}

export interface UniversityRank {
  rank: number;
  name: string;
  shortName: string;
  score: number;
  change: string; // "▲2", "-", "▼1"
  badgeColor: string;
  isMySchool?: boolean;
}

export interface ShopItem {
  id: string;
  name: string;
  category: 'clicker' | 'assistant' | 'buff';
  level: number;
  cost: number;
  effectText: string;
  cpsIncrease: number;
  clickPowerIncrease: number;
  icon: string;
  description: string;
}

export interface CampusLevelMilestone {
  level: number;
  name: string;
  title: string;
  requiredScore: number;
  description: string;
  features: string[];
  unlocked: boolean;
  isCurrent: boolean;
}
