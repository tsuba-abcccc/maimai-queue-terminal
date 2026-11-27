// Sending a command from the website when the connection fails (website review W-L7).
//
// The server keeps one command per request_id: the same request sent again returns the command
// it already holds instead of creating another one. A request whose answer was lost may still
// have reached the server, and the terminal may already have carried the command out, so the
// website never says such a request failed. It sends the same request again, unchanged, until
// the server answers, and reads a command's state again when a reading fails. Only when the
// server stays out of reach does the page say that the result is unknown and how to check it.
// The request waiting for an answer is kept in memory only.

// While a request is sent again: a command on the player's own registration (the account
// dialog, the registration detail).
export const CONFIRMING_OPERATION_NOTICE =
  '暂时无法连接服务器，正在确认这次操作的结果；确认前暂不能提交其他操作。'
// The same for a new registration (the online join, the phone registration page).
export const CONFIRMING_RESULT_TITLE = '正在确认提交结果'
export const CONFIRMING_REGISTRATION_DETAIL = '暂时无法连接服务器，正在确认这次登记的结果。请保持页面打开。'
// When the server stays out of reach.
export const UNKNOWN_OPERATION_TITLE = '暂时无法确认操作结果'
export const UNKNOWN_OPERATION_CHECK = '请在网络恢复后刷新查看登记的最新状态。'
export const UNKNOWN_OPERATION_DETAIL = `暂时无法确认这次操作是否已经完成。${UNKNOWN_OPERATION_CHECK}`

const CONNECTION_LOST_MESSAGE = '暂时无法连接服务器，请稍后重试。'
// A request without an answer after this long counts as lost.
const REQUEST_TIMEOUT_MS = 15000
// The waits before each new attempt, about a minute in all.
export const RETRY_DELAYS_MS = [1000, 2000, 4000, 8000, 15000, 30000]
// While the browser reports no network at all, a new attempt could not reach the server; the
// page waits for the network instead, up to this long in all, without using up an attempt.
const OFFLINE_WAIT_LIMIT_MS = 5 * 60 * 1000

export class ConnectionLostError extends Error {
  constructor(cause = null) {
    super(CONNECTION_LOST_MESSAGE)
    this.connectionLost = true
    this.cause = cause
  }
}

// Whether a request failed without an answer from the server: no response, no response in
// time, or an answer from a server that could not handle it (5xx). Either way the request may
// have arrived, and sending it again is safe.
export function isConnectionLost(error) {
  if (!error) return false
  if (error.connectionLost === true) return true
  return Number.isInteger(error.status) && error.status >= 500
}

// fetch, with a time limit; a request that gets no response throws ConnectionLostError.
export async function fetchWithTimeout(url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) {
  const controller = typeof AbortController === 'function' ? new AbortController() : null
  const timer = controller ? window.setTimeout(() => controller.abort(), timeoutMs) : null
  try {
    return await fetch(url, controller ? { ...options, signal: controller.signal } : options)
  } catch (error) {
    throw new ConnectionLostError(error)
  } finally {
    if (timer) window.clearTimeout(timer)
  }
}

function browserOffline() {
  return typeof navigator !== 'undefined' && navigator.onLine === false
}

// Resolves after [milliseconds], or sooner when the browser reports that it is online again or
// the page is shown again.
export function waitForConnection(milliseconds) {
  return new Promise((resolve) => {
    let timer = null
    const visible = () => {
      if (document.visibilityState === 'visible') finish()
    }
    function finish() {
      window.clearTimeout(timer)
      window.removeEventListener('online', finish)
      document.removeEventListener('visibilitychange', visible)
      resolve()
    }
    timer = window.setTimeout(finish, Math.max(0, milliseconds))
    window.addEventListener('online', finish)
    document.addEventListener('visibilitychange', visible)
  })
}

// Runs [attempt] again while it fails for want of a connection (isConnectionLost), waiting a
// little longer each time. [onConnectionLost] is called before each new attempt. [isActive] is
// asked before each new attempt; when the page no longer follows the request, it is not sent
// again. Resolves to what the attempt returned; otherwise rejects with the last error, which is
// a connection error when the attempts ran out.
export async function retryWhileConnectionLost(attempt, {
  onConnectionLost = () => {},
  isActive = () => true,
  delays = RETRY_DELAYS_MS
} = {}) {
  let retries = 0
  let offlineBudget = OFFLINE_WAIT_LIMIT_MS
  for (;;) {
    try {
      return await attempt()
    } catch (error) {
      if (!isConnectionLost(error) || retries >= delays.length || !isActive()) throw error
      onConnectionLost(error)
      await waitForConnection(delays[retries])
      retries += 1
      while (browserOffline() && offlineBudget > 0) {
        const started = Date.now()
        await waitForConnection(offlineBudget)
        offlineBudget -= Date.now() - started
      }
      if (!isActive()) throw error
    }
  }
}

// The wait before reading a command's state again after [failures] readings in a row failed.
export function retryDelay(failures) {
  return RETRY_DELAYS_MS[Math.min(Math.max(failures - 1, 0), RETRY_DELAYS_MS.length - 1)]
}
