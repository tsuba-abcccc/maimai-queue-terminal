// Sends the commands of one confirmation on the player's own registration to the terminal and
// waits for each result. The run belongs to the page, not to the account dialog or the
// registration detail that started it: closing either does not stop it, and its outcome is
// published here for whichever of them is open, and for the page's banner (website review W3).
//
// One 更改游玩偏好 confirmation can hold up to three commands (preferenceChangeCommands). Each is
// sent only after the terminal has applied the one before, against the state that one left
// behind. A command that fails ends the run; the outcome then says what was applied and which
// changes were not.
//
// A request that gets no answer is sent again unchanged, and a reading of a command's state that
// fails is made again (commandDelivery.js, website review W-L7): the command may have reached the
// terminal, so the run never says it failed. While it does so the run is `confirming`, and the
// views say why nothing else can be sent. When the server stays out of reach, the outcome says
// that the result is unknown.

import { ref } from 'vue'
import { queueActionUnavailableReason, QUEUE_ACTION_MODE_BY_OPERATION } from './queueActionAvailability.js'
import {
  CONFIRMING_OPERATION_NOTICE,
  UNKNOWN_OPERATION_CHECK,
  UNKNOWN_OPERATION_DETAIL,
  UNKNOWN_OPERATION_TITLE,
  fetchWithTimeout,
  isConnectionLost,
  retryWhileConnectionLost
} from './commandDelivery.js'

const POLL_INTERVAL_MS = 1500
const POLL_ATTEMPTS = 30

export const QUEUE_COMMAND_SUBMITTED_NOTICE = '操作已提交，正在等待现场终端确认。'
const QUEUE_COMMAND_STILL_PENDING = '操作仍在等待现场终端处理，请稍后刷新查看。'
const QUEUE_CHANGED_REFUSAL = '现场队列已经更新，请刷新后重试。'
const LOGIN_EXPIRED = '登录状态已失效，请重新登录。'

// While a run lasts: { registrationId, source, confirming }. One run at a time on the page; a
// dialog or detail opened meanwhile shows it and offers nothing else until it ends. `confirming`
// is true while a request without an answer is sent again.
export const queueCommandRun = ref(null)

// The progress line of a view while a run lasts.
export function queueCommandRunNotice() {
  const run = queueCommandRun.value
  if (!run) return ''
  return run.confirming ? CONFIRMING_OPERATION_NOTICE : QUEUE_COMMAND_SUBMITTED_NOTICE
}

// The outcome of the last run, replaced by the next one: { serial, source, registrationId,
// kind ('success' | 'pending' | 'unknown' | 'error'), title, notice, error, loginRequired }.
// `notice` is what the terminal applied, `error` what it did not and why; for 'unknown', which
// changes may or may not have been made.
export const queueCommandOutcome = ref(null)
let outcomeSerial = 0

const PREFERENCE_LABELS = { SOLO: '单人游玩', OPEN_TO_JOIN: '允许他人加入' }

// The title of a completed action, as the terminal titles the same remote operation on its home
// page (remoteQueueOperationFeedbackTitle). Failures and partial completion keep the general
// titles.
const COMPLETED_OPERATION_TITLES = {
  DEFER_ONE_ROUND: '登记将暂缓一次',
  CANCEL_DEFER_ONE_ROUND: '暂缓一次已取消',
  SKIP_ONE_TURN: '登记将轮空一次',
  CANCEL_SKIP_ONE_TURN: '轮空一次已取消',
  TEMPORARILY_LEAVE: '登记已设为暂时离开',
  CANCEL_TEMPORARY_LEAVE: '暂时离开已取消',
  TRANSFER_MACHINE: '登记已转至其他机台',
  CHANGE_PLAY_PREFERENCE: '游玩偏好已更改',
  LEAVE_QUEUE: '登记已退出排队',
  SET_PAIRED_MACHINE_QUEUEING: '两台机台之间的安排已更改',
  SET_ALLOWS_PASSING: '游玩偏好已更改',
  CREATE_FIXED_PAIR: '固定组合已建立',
  RENAME_REGISTRATION: '登记昵称已修改'
}

// One 更改游玩偏好 confirmation can hold several commands; the terminal titles each of them. The
// website shows one result for the confirmation and takes the title of its first command: the
// play preference, or the tick box inside it, comes before the tick box for the two machines.
function completedTitle(commands) {
  return COMPLETED_OPERATION_TITLES[commands[0]?.operation] || '操作已完成'
}

// What one command of a 更改游玩偏好 confirmation changes, named as its option or tick box is.
function preferenceChangeText(command, pairedMachineName) {
  if (command.operation === 'CHANGE_PLAY_PREFERENCE') {
    const label = PREFERENCE_LABELS[command.extra?.preference]
    return label ? `本次游玩偏好改为“${label}”` : '更改本次游玩偏好'
  }
  if (command.operation === 'SET_ALLOWS_PASSING') {
    // The tick box of a fixed pair is the pair's choice (preferenceChangeCommands).
    const subject = command.fixedPair ? '这组固定组合' : '这份登记'
    return command.extra?.allows_passing
      ? `允许后方的登记越过${subject}`
      : `不再允许后方的登记越过${subject}`
  }
  if (command.operation === 'SET_PAIRED_MACHINE_QUEUEING') {
    // A machine name is set off by spaces (copy guide); 两台机台 is not.
    const machines = pairedMachineName ? `当前机台和 ${pairedMachineName} ` : '两台机台'
    return command.extra?.also_queues_at_paired_machine
      ? `允许系统在${machines}之间安排登记`
      : `不再允许系统在${machines}之间安排登记`
  }
  return ''
}

function sentence(text) {
  const trimmed = String(text || '').trim()
  if (!trimmed) return ''
  return /[。？！]$/.test(trimmed) ? trimmed : `${trimmed}。`
}

function createRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16)
    const result = character === 'x' ? value : (value & 0x3) | 0x8
    return result.toString(16)
  })
}

function wait(milliseconds) {
  return new Promise((resolve) => { window.setTimeout(resolve, milliseconds) })
}

function registrationAbsenceStatus(registration) {
  if (registration.deferred_once) return 'DEFER_ONE_ROUND'
  if (registration.skips_one_turn) return 'SKIP_ONE_TURN'
  if (registration.temporarily_away) return 'TEMPORARILY_AWAY'
  return 'NONE'
}

// The request for one command, with the state it was decided on: the terminal refuses it when
// the registration has changed since.
function queueOperationPayload(state, registration, operation, extra = {}) {
  return {
    request_id: createRequestId(),
    operation,
    expected_queue_id: state.queue.queue_id,
    expected_registration_id: registration.registration_id,
    expected_machine_id: registration.machine_id,
    expected_position: registration.position,
    expected_fixed_pair_id: registration.fixed_pair_id || null,
    expected_absence_status: registrationAbsenceStatus(registration),
    expected_temporary_away_skipped_turns: registration.temporary_away_skipped_turns || 0,
    expected_pending_check_in: registration.online_registration_pending_check_in === true,
    expected_machine_configuration_revision: state.queue.machine_configuration_revision,
    expected_machine_stable_id: registration.machine_stable_id,
    ...extra
  }
}

class QueueCommandError extends Error {
  constructor(message, code = null, status = null) {
    super(message)
    this.code = code
    this.status = status
  }
}

// A command that may or may not have been carried out: its request or the reading of its state
// got no answer, also after every new attempt.
class QueueCommandResultUnknown extends Error {}

// A request without a response throws ConnectionLostError (commandDelivery.js).
async function requestJson(url, options, fallbackMessage) {
  const response = await fetchWithTimeout(url, { credentials: 'include', cache: 'no-store', ...options })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new QueueCommandError(payload.error || fallbackMessage, payload.code || null, response.status)
  }
  return payload
}

// Waits for the terminal's answer to one command: { status: 'APPLIED' | 'REJECTED' | 'PENDING',
// detail }. Reading the result again after a failed read, as the pages always did; a reading
// that gets no answer is made again, a little later each time, without counting as a reading.
async function pollCommand(commandApiBase, commandId, delivery) {
  let lastError = null
  for (let attempt = 0; attempt <= POLL_ATTEMPTS; attempt += 1) {
    if (attempt > 0) await wait(POLL_INTERVAL_MS)
    try {
      const payload = await retryWhileConnectionLost(() => requestJson(
        `${commandApiBase}/${encodeURIComponent(commandId)}`,
        { headers: { Accept: 'application/json' } },
        '暂时无法读取现场处理结果。'
      ), delivery)
      delivery.onAnswer()
      if (payload.status === 'PENDING' && attempt < POLL_ATTEMPTS) continue
      if (payload.status === 'APPLIED') {
        return { status: 'APPLIED', detail: payload.result_detail || '现场终端已完成这次操作。' }
      }
      if (payload.status === 'PENDING') return { status: 'PENDING', detail: QUEUE_COMMAND_STILL_PENDING }
      return { status: 'REJECTED', detail: payload.result_detail || '现场终端没有执行这次操作。' }
    } catch (error) {
      if (isConnectionLost(error)) throw new QueueCommandResultUnknown()
      delivery.onAnswer()
      if (error.code === 'ACCOUNT_LOGIN_REQUIRED') throw error
      lastError = error
    }
  }
  throw lastError || new QueueCommandError('暂时无法读取现场处理结果。')
}

// Sends one command and waits for its result. The request is made once, with its request_id,
// and sent again unchanged while it gets no answer: the server then returns the command it
// already holds.
async function sendCommand({ accountApiUrl, commandApiBase, csrfHeaders }, state, registration, command, delivery) {
  const payload = queueOperationPayload(state, registration, command.operation, command.extra)
  const body = JSON.stringify(payload)
  let response
  try {
    response = await retryWhileConnectionLost(() => requestJson(`${accountApiUrl}/queue-commands`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
      body
    }, '暂时无法提交这次操作。'), delivery)
  } catch (error) {
    if (isConnectionLost(error)) throw new QueueCommandResultUnknown()
    throw error
  }
  delivery.onAnswer()
  // The server takes the request_id as the command's id.
  return pollCommand(commandApiBase, response.command_id || payload.request_id, delivery)
}

// The account's queue as the server has it after the terminal applied the command before. A
// reading that gets no answer is made again; the next command is sent only after it.
async function readAccountQueue(accountApiUrl, delivery) {
  const queue = await retryWhileConnectionLost(() => requestJson(
    accountApiUrl + '/queue',
    { headers: { Accept: 'application/json' } },
    QUEUE_CHANGED_REFUSAL
  ), delivery)
  delivery.onAnswer()
  return queue
}

function publish(outcome) {
  outcomeSerial += 1
  queueCommandOutcome.value = { ...outcome, serial: outcomeSerial }
  return queueCommandOutcome.value
}

// Runs the [commands] ([{ operation, extra }], at least one) on [registration], decided on
// [state] (the account's queue). [source] names the view that started the run. The caller has
// already checked that the first command can be sent. [pairedMachineName] names the other
// machine of the pair in the outcome of a 更改游玩偏好 confirmation. Resolves to the outcome,
// which is also published in queueCommandOutcome.
export async function runQueueCommands({
  source,
  registration,
  state,
  commands,
  pairedMachineName = null,
  queueOperations = null,
  accountApiUrl,
  commandApiBase,
  csrfHeaders = () => ({})
}) {
  if (queueCommandRun.value || !registration || !state?.queue || !commands?.length) return null
  const registrationId = registration.registration_id
  queueCommandRun.value = { registrationId, source, confirming: false }
  const connection = { accountApiUrl, commandApiBase, csrfHeaders }
  // Marks the run while a request without an answer is sent again, and clears the mark once
  // the server answers.
  const setConfirming = (confirming) => {
    if (queueCommandRun.value && queueCommandRun.value.confirming !== confirming) {
      queueCommandRun.value = { ...queueCommandRun.value, confirming }
    }
  }
  const delivery = {
    onConnectionLost: () => setConfirming(true),
    onAnswer: () => setConfirming(false)
  }
  const several = commands.length > 1
  const applied = []
  // The changes that were not made, from the failed command on, and why.
  const unfinished = (index, reason) => {
    if (!several) return sentence(reason)
    const changes = commands.slice(index)
      .map((command) => preferenceChangeText(command, pairedMachineName))
      .filter(Boolean)
    return `以下更改未完成：${changes.join('；')}。${sentence(reason)}`
  }
  // The command the terminal has not answered yet, and the ones that were therefore not sent.
  const stillPending = (index) => {
    const waiting = preferenceChangeText(commands[index], pairedMachineName)
    const unsent = commands.slice(index + 1)
      .map((command) => preferenceChangeText(command, pairedMachineName))
      .filter(Boolean)
    return `以下更改仍在等待现场终端处理：${waiting}。请稍后刷新查看。` +
      (unsent.length ? `以下更改尚未提交：${unsent.join('；')}。` : '')
  }
  // The command whose result is unknown, and the ones that were therefore not sent.
  const resultUnknown = (index) => {
    if (!several) return UNKNOWN_OPERATION_DETAIL
    const uncertain = preferenceChangeText(commands[index], pairedMachineName)
    const unsent = commands.slice(index + 1)
      .map((command) => preferenceChangeText(command, pairedMachineName))
      .filter(Boolean)
    return `以下更改暂时无法确认是否已经完成：${uncertain}。${UNKNOWN_OPERATION_CHECK}` +
      (unsent.length ? `以下更改尚未提交：${unsent.join('；')}。` : '')
  }
  const failure = (index, reason, extra = {}) => ({
    source,
    registrationId,
    kind: 'error',
    title: applied.length ? '操作未全部完成' : '操作未完成',
    notice: applied.join(''),
    error: unfinished(index, reason),
    loginRequired: false,
    ...extra
  })

  let current = registration
  let currentState = state
  try {
    for (const [index, command] of commands.entries()) {
      if (index > 0) {
        currentState = await readAccountQueue(accountApiUrl, delivery)
        current = currentState?.registrations?.find((candidate) => (
          candidate.registration_id === registrationId
        ))
        if (!current || !currentState?.queue) return publish(failure(index, QUEUE_CHANGED_REFUSAL))
        const reason = queueActionUnavailableReason({
          terminalOnline: Boolean(currentState.queue.terminal_online),
          remoteActions: Boolean(currentState.queue.remote_actions),
          registration: current,
          mode: QUEUE_ACTION_MODE_BY_OPERATION[command.operation],
          queueOperations
        })
        if (reason) return publish(failure(index, reason))
      }
      const result = await sendCommand(connection, currentState, current, command, delivery)
      if (result.status === 'APPLIED') {
        applied.push(sentence(result.detail))
        continue
      }
      if (result.status === 'PENDING') {
        // Not a failure: the terminal may still carry this command out. The ones after it were
        // never sent, and the outcome says so; everything goes into the notice, which every
        // view shows, a banner included.
        return publish({
          source,
          registrationId,
          kind: 'pending',
          title: '等待现场终端处理',
          notice: several ? `${applied.join('')}${stillPending(index)}` : result.detail,
          error: '',
          loginRequired: false
        })
      }
      return publish(failure(index, result.detail))
    }
    return publish({
      source,
      registrationId,
      kind: 'success',
      title: completedTitle(commands),
      notice: applied.join(''),
      error: '',
      loginRequired: false
    })
  } catch (error) {
    const index = applied.length
    if (error instanceof QueueCommandResultUnknown) {
      // Not a failure: the command may have been carried out. Nothing after it was sent.
      return publish({
        source,
        registrationId,
        kind: 'unknown',
        title: UNKNOWN_OPERATION_TITLE,
        notice: applied.join(''),
        error: resultUnknown(index),
        loginRequired: false
      })
    }
    if (error.code === 'ACCOUNT_LOGIN_REQUIRED') {
      return publish(failure(index, LOGIN_EXPIRED, { loginRequired: true }))
    }
    return publish(failure(index, error.message || '暂时无法提交这次操作。'))
  } finally {
    queueCommandRun.value = null
  }
}
