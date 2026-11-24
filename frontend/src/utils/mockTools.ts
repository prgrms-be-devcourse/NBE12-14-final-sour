// Vite sets these from its command and mode. Neither value can come from a browser URL.
declare global {
  const __CAMPUS_MOCK_TOOLS__: boolean;
  const __CAMPUS_SERVICE_BUILD__: boolean;
}

// Node's source-level tests exercise the prototype; ordinary non-Vite consumers default to no tools.
export const MOCK_TOOLS = typeof __CAMPUS_MOCK_TOOLS__ === 'undefined'
  ? typeof process !== 'undefined' && Boolean(process.env.NODE_TEST_CONTEXT)
  : __CAMPUS_MOCK_TOOLS__;

export const SERVICE_BUILD = typeof __CAMPUS_SERVICE_BUILD__ !== 'undefined' && __CAMPUS_SERVICE_BUILD__;
