/**
 * Centralized API helper to handle both JWT and service token authentication
 */

/**
 * Get JWT token from localStorage
 */
function getJwtToken(): string | null {
  return localStorage.getItem("auth_token");
}

/**
 * Get service token from URL query parameters
 */
function getServiceToken(): string | null {
  const params = new URLSearchParams(window.location.search);
  return params.get('servicetoken');
}

/**
 * Get authentication headers based on available tokens
 * Service token takes precedence over JWT
 */
export function getAuthHeaders(): HeadersInit {
  const serviceToken = getServiceToken();
  if (serviceToken) {
    return {
      'X-Service-Token': serviceToken,
    };
  }

  const jwtToken = getJwtToken();
  if (jwtToken) {
    return {
      'Authorization': `Bearer ${jwtToken}`,
    };
  }

  return {};
}

/**
 * Add authentication to fetch options
 */
export function withAuth(options: RequestInit = {}): RequestInit {
  return {
    ...options,
    headers: {
      ...options.headers,
      ...getAuthHeaders(),
    },
  };
}

/**
 * Add service token to URL if present
 */
export function addServiceTokenToUrl(url: URL): void {
  const token = getServiceToken();
  if (token) {
    url.searchParams.set('servicetoken', token);
  }
}
