import { createContext, useContext } from 'react';
import type { OtherClicksFeed } from '../utils/otherClicksFeed';

/**
 * API mode provides the feed from the root click queue (SCRUM-159). Without a provider (mock mode, isolated
 * mockups) the value is null and the campus keeps polling the mock source.
 */
export const OtherClicksFeedContext = createContext<OtherClicksFeed | null>(null);

export function useOtherClicksFeed(): OtherClicksFeed | null {
  return useContext(OtherClicksFeedContext);
}
