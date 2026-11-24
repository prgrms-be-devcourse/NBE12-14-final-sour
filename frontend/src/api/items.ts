import { useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';

/** ItemType.java / PayType.java. Credit products stay disabled until payments are integrated. */
export type ItemType = 'MULTIPLIER' | 'LOGO_UNIVERSITY' | 'LOGO_DEPARTMENT' | 'LOGO_PROFILE';
export type PayType = 'CONTRIBUTION' | 'CREDIT';
export interface ShopItem {
  id: number;
  name: string;
  content: string;
  type: ItemType;
  payType: PayType;
  price: number;
  durationSeconds: number | null;
}
export interface ItemPurchaseResponse {
  userItemId: number;
  item: { id: number; name: string; payType: PayType };
  price: number;
  expiredAt: string | null;
  myContribScore: number;
  myCreditBalance: number;
}
/** PurchaseHistoryResponse.java. Missing catalog entries and permanent items are nullable. */
export interface PurchaseHistoryItem {
  userItemId: number;
  item: { id: number; name: string; payType: PayType } | null;
  price: number;
  createdAt: string;
  expiredAt: string | null;
}
export interface PurchaseHistoryResponse {
  items: PurchaseHistoryItem[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
}
export interface ActiveItemEffect {
  itemId: number;
  name: string | null;
  multiplier: number;
  expiredAt: string;
  remainingSeconds: number;
}
export interface EffectSnapshot { effect: ActiveItemEffect | null; receivedAt: number }
export const itemKeys = {
  catalog: ['items', 'catalog'] as const,
  histories: ['items', 'history'] as const,
  history: (userId: number) => ['items', 'history', userId] as const,
  historyPage: (userId: number, page: number) => ['items', 'history', userId, page] as const,
  active: (userId: number | undefined, generation?: number) => generation === undefined
    ? ['items', 'active', userId] as const : ['items', 'active', userId, generation] as const,
};
export function getItems(client: ApiClient, signal?: AbortSignal): Promise<ShopItem[]> {
  return client.get('/items', { anonymous: true, signal });
}
export function getActiveItemEffect(client: ApiClient, signal?: AbortSignal): Promise<ActiveItemEffect | null> {
  return client.get('/items/me/active', { signal });
}
/** Server order: createdAt DESC, userItemId DESC. Do not sort a single page client-side. */
export function getPurchaseHistory(client: ApiClient, page = 0, signal?: AbortSignal): Promise<PurchaseHistoryResponse> {
  return client.get('/items/me/history', { query: { page, size: 20 }, signal });
}
/** Contribution potions only. No body, multipart, idempotency key or automatic POST retry. */
export function purchaseItem(client: ApiClient, itemId: number, signal?: AbortSignal): Promise<ItemPurchaseResponse> {
  return client.request(`/items/${itemId}/purchase`, { method: 'POST', signal });
}
export function useItemCatalog() {
  const client = useApiClient();
  return useQuery({ queryKey: itemKeys.catalog, queryFn: ({ signal }) => getItems(client, signal) });
}
/** Use elapsed monotonic time, not the browser wall clock or a decrement-per-timer counter. */
export function effectSecondsLeft(snapshot: EffectSnapshot | undefined, now: number): number {
  if (!snapshot?.effect) return 0;
  return Math.max(0, Math.ceil(snapshot.effect.remainingSeconds - Math.max(0, now - snapshot.receivedAt) / 1000));
}
