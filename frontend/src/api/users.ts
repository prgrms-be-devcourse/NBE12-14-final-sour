// The signed-in member's own profile (SCRUM-153): GET /api/v1/users/me (UserController.me, MyInfoResponse.java).
// Nickname change (SCRUM-166): PATCH /api/v1/users/me (UserController.updateProfile, UpdateProfileRequest.java).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from './ApiProvider';
import type { ApiClient } from './client';
import type { UserRole } from './auth';

/** `UserStatus.java`, in declaration order (checked by tests/apiMyInfo.test.ts). */
export const USER_STATUSES = ['ACTIVE', 'SUSPENDED', 'BANNED'] as const;
export type UserStatus = (typeof USER_STATUSES)[number];

/** `MyInfoResponse`. Scores are Redis live values when the server has them, otherwise the DB values. */
export interface MyInfo {
  id: number;
  nickname: string;
  email: string;
  role: UserRole;
  status: UserStatus;
  profileImageUrl: string | null;
  university: { id: number; name: string; logoUrl: string | null };
  department: { id: number; name: string };
  /** `level` is the clicker ID; `nextLevelRequiredScore` is null at the top level. */
  clicker: { level: number; clickPower: number; nextLevelRequiredScore: number | null };
  totalContribScore: number;
  totalAttackScore: number;
}

/** Needs a signed-in session; without one the server answers 401 COMMON_UNAUTHORIZED. */
export function getMyInfo(client: ApiClient): Promise<MyInfo> {
  return client.get<MyInfo>('/users/me');
}

export const userKeys = {
  /** Belongs to whoever is signed in, so its data is reset on sign-out (ApiSessionWatcher). */
  me: ['users', 'me'] as const,
};

/** The profile while signed in. Signed out, the query stays idle and has no data. */
export function useMyInfo() {
  const client = useApiClient();
  const signedIn = useAuthSession().status === 'signed-in';
  return useQuery({ queryKey: userKeys.me, queryFn: () => getMyInfo(client), enabled: signedIn });
}

/**
 * UpdateProfileRequest has only `nickname`: the department cannot be changed, and the profile image changes only by
 * buying the personal logo item. Answers MyInfoResponse; 409 USER_NICKNAME_DUPLICATED, 400 COMMON_INVALID_INPUT.
 */
export function updateMyProfile(client: ApiClient, request: { nickname: string }): Promise<MyInfo> {
  return client.request<MyInfo>('/users/me', { method: 'PATCH', body: request });
}

/**
 * Changes the nickname and puts it into the cached profile, which the game screens read (ApiPlayerSync). Only the
 * nickname is taken from the answer: its scores may be older than clicks the cache took in meanwhile.
 */
export function useUpdateNickname() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (nickname: string) => updateMyProfile(client, { nickname }),
    onSuccess: async (result) => {
      await queryClient.cancelQueries({ queryKey: userKeys.me, exact: true });
      queryClient.setQueryData<MyInfo>(userKeys.me, (current) => current?.id === result.id ? { ...current, nickname: result.nickname } : current);
    },
  });
}

/**
 * PATCH /users/me/password (SCRUM-167). 204 means the server revoked every refresh token, expired the cookie and
 * dropped the login session, so this browser closes its session at once: a request still on its way with the old
 * token would otherwise try to reissue and end it as "expired". 400 USER_INVALID_PASSWORD: wrong current password.
 */
export async function changePassword(client: ApiClient, request: { currentPassword: string; newPassword: string }): Promise<void> {
  await client.request<void>('/users/me/password', { method: 'PATCH', body: request });
  client.session.close('password-changed');
}

/**
 * POST /users/me/withdrawal (SCRUM-167). 204 is the same session end as a password change. 400
 * USER_INVALID_PASSWORD: wrong password; 403 USER_SUSPENDED ends the session with the suspension notice (client.ts).
 */
export async function withdraw(client: ApiClient, password: string): Promise<void> {
  await client.request<void>('/users/me/withdrawal', { method: 'POST', body: { password } });
  client.session.close('withdrawn');
}

/** Whether to show the admin menu (SCRUM-171). The server checks the role again on every admin call. */
export function useIsAdmin(): boolean {
  const me = useMyInfo();
  return useAuthSession().status === 'signed-in' && me.data?.role === 'ADMIN';
}

/** What the game screens take from the profile. Clicker power and costs stay on the mock table until SCRUM-157. */
export interface AccountProfile {
  userId: string;
  schoolId: number;
  schoolName: string;
  department: string;
  nickname: string;
  email: string;
  contribution: number;
  clickerLevel: number;
}

export function accountFromMyInfo(info: MyInfo): AccountProfile {
  return {
    userId: String(info.id),
    schoolId: info.university.id,
    schoolName: info.university.name,
    department: info.department.name,
    nickname: info.nickname,
    email: info.email,
    contribution: info.totalContribScore,
    clickerLevel: info.clicker.level,
  };
}
