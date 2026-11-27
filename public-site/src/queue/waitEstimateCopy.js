// A shown wait in words, as the terminal writes it (formatWaitAmount; maintainer, 2026-11-28):
// below an hour in minutes (约 45 分钟), from an hour on in hours and minutes without a zero (约 1 小时
// 15 分钟, 约 2 小时), above two hours 2 小时以上 without 约 or 最快. The terminal sends minutes rounded
// by its steps (1 up to 45, 5 below 90, 10 up to 120); above 120 any value means 2 小时以上.
export const LONG_WAIT_MINUTES = 120
export const LONG_WAIT_TEXT = '2 小时以上'

export function formatWaitAmount(minutes, earliest = false) {
  if (minutes > LONG_WAIT_MINUTES) return LONG_WAIT_TEXT
  let amount
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60)
    const rest = minutes % 60
    amount = rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分钟`
  } else {
    amount = `${minutes} 分钟`
  }
  return earliest ? `最快约 ${amount}` : `约 ${amount}`
}

// [amount] after a word in Chinese: a space before a leading number, as 这份登记 2 小时以上.
function afterWord(amount) {
  return /^\d/.test(amount) ? ` ${amount}` : amount
}

// Wait estimates count pending online registrations as checking in, as the waiting positions
// are grouped. When some of those ahead may not check in, the terminal sends the earlier minutes
// a registration then has (`wait_minutes_if_online_missing`), only where they show earlier. The
// sentence is the terminal's (maintainer, 2026-11-26). [subject] is 这份登记 or 这个位置.
export function onlineMissingWaitNote(subject, minutes) {
  if (minutes === null || minutes === undefined) return null
  return minutes <= 0
    ? `前方的线上登记未按时签到时，${subject}可能很快可以游玩。`
    : `前方的线上登记未按时签到时，${subject}${afterWord(formatWaitAmount(minutes, true))}后可以游玩。`
}

// For a 最快 wait far from the wait if the system arranges nobody between the two machines of a
// pair (`estimated_wait_upper_minutes`, sent 24 minutes or more later): that other time with its
// condition, on its own line under the wait. The two are not written as a range: the wait will be
// near one of them, not anywhere in between. The sentence is the terminal's (maintainer,
// 2026-11-28: “写出来各种情况，比如‘最快约X分钟。如果XXX，约X分钟’”). [subject] is 这份登记 or
// 这个位置.
export function noArrangementWaitNote(subject, minutes) {
  if (minutes === null || minutes === undefined) return null
  return `系统未在两台机台之间安排登记时，${subject}${afterWord(formatWaitAmount(minutes))}后可以游玩。`
}

// The value under 新登记预计等待 or 转入后预计等待, so that it does not repeat 预计 (terminal:
// formatJoinWaitEstimate).
export function newRegistrationWaitText(minutes) {
  if (minutes === null || minutes === undefined) return '暂时无法估算'
  return minutes <= 0 ? '很快可以游玩' : formatWaitAmount(minutes)
}

// The same value while joining with the tick box for the two machines on: [pairedEarliestMinutes]
// is the wait of a new registration at the other machine of the pair, null where that machine
// cannot take one. When it is the earlier one it is shown, as the earliest.
export function newRegistrationWaitTextWithPaired(minutes, pairedEarliestMinutes) {
  const hasMinutes = minutes !== null && minutes !== undefined
  if (
    pairedEarliestMinutes === null || pairedEarliestMinutes === undefined ||
    (hasMinutes && pairedEarliestMinutes >= minutes)
  ) return newRegistrationWaitText(minutes)
  return pairedEarliestMinutes <= 0 ? '可能很快可以游玩' : formatWaitAmount(pairedEarliestMinutes, true)
}

export const NEW_REGISTRATION_WAIT_LABEL = '新登记预计等待'
export const NEW_REGISTRATION_WAIT_NOTE = '按当前队列估算，实际以轮到时为准。'

// What waits at a machine, as the terminal's machine lists say it: [emptyText] for a machine
// without registrations, otherwise the waiting positions and the registrations. The count of
// waiting positions is left out where the page does not know it.
export function machineQueueSummary({ registrationCount, waitingPositionCount = null, emptyText }) {
  if (!registrationCount) return emptyText
  if (waitingPositionCount === null || waitingPositionCount === undefined) {
    return `${registrationCount} 份登记`
  }
  return `${waitingPositionCount} 个等待位置·${registrationCount} 份登记`
}

// The warning that a new registration may not play before closing time, as the terminal asks it
// while joining (JoinClosingWarningDialog) and by its rule (estimatedWaitExtendsPastClosing): the
// wait of a new registration, counted from now, ends later than the closing time of the business
// hours in force. The online join and the phone page share it.
export const CLOSING_WARNING_TITLE = '可能无法在闭店前游玩'
export const CLOSING_WARNING_CONFIRM = '仍然继续'

// [closesAtMillis] is null where no business hours are in force.
export function waitExtendsPastClosing(minutes, closesAtMillis, nowMillis = Date.now()) {
  if (closesAtMillis === null || closesAtMillis === undefined) return false
  if (minutes === null || minutes === undefined || minutes < 0) return false
  return nowMillis + minutes * 60000 > closesAtMillis
}

// The sentence of the warning for one machine. [closingTime] is the closing time as HH:mm, or
// null. [alternativeMachineNames] are the machines the player could choose instead; the sentence
// about them is left out when there are none.
export function closingWarningText({ machineName, minutes, closingTime, alternativeMachineNames = [] }) {
  const estimateText = minutes === null || minutes === undefined
    ? '游玩时间'
    : `${formatWaitAmount(minutes)}后才能游玩，`
  const alternativeText = alternativeMachineNames.length
    ? `也可以取消并改选${alternativeMachineNames.join('、')}；按照当前队列估算，改选后更可能在闭店前游玩。`
    : ''
  return `加入 ${machineName} 后，${estimateText}可能晚于 ${closingTime || '今日闭店时间'}。${alternativeText}现场进度仍可能变化，是否继续创建登记？`
}
