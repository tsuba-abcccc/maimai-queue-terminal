// Which remote action on a player's own registration can be carried out right now, and the
// sentence shown when one cannot. The rules are those of the terminal and the server; the
// account dialog and the registration details of the status page share them word for word.

import { machineQueueSummary, newRegistrationWaitText } from './waitEstimateCopy.js'

// The modes of the action panels, by the operation they send to the terminal.
export const QUEUE_ACTION_MODE_BY_OPERATION = {
  DEFER_ONE_ROUND: 'defer',
  TEMPORARILY_LEAVE: 'temporary_leave',
  CANCEL_DEFER_ONE_ROUND: 'cancel_defer',
  SKIP_ONE_TURN: 'skip_one_turn',
  CANCEL_SKIP_ONE_TURN: 'cancel_skip_one_turn',
  CANCEL_TEMPORARY_LEAVE: 'cancel_temporary_leave',
  TRANSFER_MACHINE: 'transfer',
  CHANGE_PLAY_PREFERENCE: 'preference',
  SET_PAIRED_MACHINE_QUEUEING: 'preference',
  SET_ALLOWS_PASSING: 'preference',
  CREATE_FIXED_PAIR: 'friend_pair',
  RENAME_REGISTRATION: 'rename',
  LEAVE_QUEUE: 'leave'
}

// Nobody is held at a machine that cannot be played on: leaving the queue and moving to
// another machine stay possible while the machine of a registration is stopped.
const STOPPED_MACHINE_ACTION_MODES = ['leave', 'transfer']

export const STOPPED_MACHINE_ACTION_NOTICE =
  '当前机台已停止使用，恢复正常使用前只能退出排队或转至其他机台。'

// Deferring, skipping a turn and leaving temporarily are for registrations that wait. One in
// the playing position has to be taken back to the head of the waiting order on the terminal
// first.
const PLAYING_POSITION_REFUSALS = {
  defer: '处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。',
  skip_one_turn: '处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。',
  temporary_leave: '处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。'
}

// Only the management app may postpone a checked-in online registration before it has played
// a round, or defer one again whose 暂缓一次 has been used since its last round
// (maintainer, 2026-11-25).
const POSTPONEMENT_MODES = ['postpone', 'defer', 'skip_one_turn', 'temporary_leave']
const ONLINE_REGISTRATION_NOT_PLAYED_REFUSAL =
  '已签到的线上登记在游玩一轮之前不能延后。'
const DEFER_ONE_ROUND_USED_REFUSAL = '这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。'

// Operations that older terminals cannot carry out. They are offered only where the terminal has
// declared them (capabilities.queue_operations); otherwise the card or button says why. The
// server refuses them with the same reason (website review W2). The tick box that lets others
// pass is not shown at all without SET_ALLOWS_PASSING, and neither are 与朋友共同游玩 and the
// entry of 修改登记昵称 without their operations; their reason here only answers a terminal that
// stopped declaring them while the panel was open.
const TERMINAL_DECLARED_OPERATION_BY_MODE = {
  skip_one_turn: 'SKIP_ONE_TURN',
  cancel_skip_one_turn: 'CANCEL_SKIP_ONE_TURN',
  friend_pair: 'CREATE_FIXED_PAIR',
  rename: 'RENAME_REGISTRATION'
}
const TERMINAL_NOT_UPDATED_REFUSALS = {
  skip_one_turn: '现场终端尚未更新，暂不能使用轮空一次。',
  cancel_skip_one_turn: '现场终端尚未更新，暂不能使用这项操作。',
  friend_pair: '现场终端尚未更新，暂不能使用这项操作。',
  rename: '现场终端尚未更新，暂不能使用这项操作。'
}

// What 轮空一次 does, where the account dialog and the status page explain the choices.
export const SKIP_ONE_TURN_EXPLANATION =
  '下一次轮到时移至队尾，计一次轮空，随后自动解除。'

// 轮空一次 shares the count of skipped turns with 暂时离开, and three are the limit. Few skip three
// times in a row, so the limit is only stressed once it is near: when this skip will be the third,
// and when the next turn removes the registration. The same sentences as on the terminal
// (skipOneTurnLimitNotice). [subject] is who has skipped: 这份登记 where the state is shown, the
// registration by name in the confirmation of 轮空一次, as on the terminal. The terminal sends the
// count at all times (2026-11-28), so the confirmation can say it before the player confirms; an
// older terminal sends it only once 轮空一次 or 暂时离开 is set, and the confirmation then says
// nothing.
export function skipOneTurnLimitNotice(skippedTurns, subject = '这份登记') {
  if (skippedTurns >= 3) {
    return `${subject}已轮空 ${skippedTurns} 次。下一次轮到时，登记会被自动移除，而不会移至队尾。`
  }
  if (skippedTurns === 2) {
    return `${subject}已轮空 2 次，这次将是第 3 次。此后再轮到时，如果仍设置了轮空一次或暂时离开，登记会被自动移除。`
  }
  return ''
}

// In the confirmation of 暂时离开, as on the terminal: the turns already missed are kept and
// counted on.
export function temporaryLeaveRetainedTurnsNotice(skippedTurns, subject = '这份登记') {
  return skippedTurns > 0
    ? `${subject}已轮空 ${skippedTurns} 次。再次暂时离开后，已轮空次数将继续累计。`
    : ''
}

// The time limit of an online registration that still has to check in, in the terminal's two
// sentences (registration menu): counted from its creation, or started again. The terminal says
// it to a player who stands in front of it; away from the venue, the player's own registration
// adds once where and how to check in ([tellsHowToCheckIn]).
export function onlineCheckInLimitText({ restarted = false, tellsHowToCheckIn = false } = {}) {
  const limit = restarted
    ? '这份登记的 30 分钟签到时限已重新开始计时。请在本次时限内完成现场签到；超过 30 分钟，或轮到进入游玩位置时仍未签到，这份登记会自动移除。'
    : '请在创建线上登记后的 30 分钟内完成现场签到。超过 30 分钟，或轮到进入游玩位置时仍未签到，这份登记会自动移除。'
  return tellsHowToCheckIn
    ? `${limit}请到现场终端点击本人的登记并选择“已到场”。`
    : limit
}

// `registration` as the account API reports it; `mode` as in QUEUE_ACTION_MODE_BY_OPERATION;
// `queueOperations` as capabilities.queue_operations of the status API, absent from older
// servers. Returns an empty string when the action can be sent.
export function queueActionUnavailableReason({
  terminalOnline,
  remoteActions,
  registration,
  mode,
  queueOperations
}) {
  if (!terminalOnline) {
    return '现场终端暂时离线，无法执行这项操作。终端恢复同步后即可重试。'
  }
  if (
    registration?.machine_operational === false &&
    !STOPPED_MACHINE_ACTION_MODES.includes(mode)
  ) {
    return STOPPED_MACHINE_ACTION_NOTICE
  }
  if (!remoteActions) {
    return '现场未开启网页端远程操作，当前只能查看状态。'
  }
  if (registration?.position === 'PLAYING' && PLAYING_POSITION_REFUSALS[mode]) {
    return PLAYING_POSITION_REFUSALS[mode]
  }
  if (POSTPONEMENT_MODES.includes(mode) && registration?.online_registration_not_played === true) {
    return ONLINE_REGISTRATION_NOT_PLAYED_REFUSAL
  }
  if (mode === 'defer' && registration?.defer_one_round_used === true) {
    return DEFER_ONE_ROUND_USED_REFUSAL
  }
  const declaredOperation = TERMINAL_DECLARED_OPERATION_BY_MODE[mode]
  if (
    declaredOperation &&
    !(Array.isArray(queueOperations) && queueOperations.includes(declaredOperation))
  ) {
    return TERMINAL_NOT_UPDATED_REFUSALS[mode]
  }
  return ''
}

// 延后这份登记, as on the terminal (maintainer, 2026-11-25: “网页版的原则就是尽量和终端保持UX一致”).
// One entry opens the three methods as comparison cards; a card opens its confirmation, whose
// 返回 goes back to the cards. The texts are the terminal's, word for word; where the terminal
// names both players of a fixed pair, the website, which knows only its own registration, says
// 这组登记.

export function postponeChooserHeading(registration) {
  return registration?.fixed_pair
    ? {
        title: '延后这组登记',
        intro: '这组登记是固定组合，所选方式会同时作用于两份登记。'
      }
    : {
        title: '延后这份登记',
        intro: '暂不能游玩时，请选择一种方式。三种方式的区别如下。'
      }
}

// `unavailableReason(mode)` gives the shared reason for a mode, or an empty string; the venue's
// rules for 暂缓一次 and 暂时离开 come first. 轮空一次 has no such rule.
export function postponeChoiceCards(rules, unavailableReason) {
  return [
    {
      mode: 'defer',
      title: '暂缓一次',
      summary: '跳过一次，位置不变',
      facts: [
        ['适用', '仍在现场，只是这一次轮到时还没有准备好。'],
        ['轮到时', '跳过这一次进入游玩位置的机会，登记保持当前顺序；本轮由其余在场的登记重新组合。'],
        ['结束', '跳过一次后自动解除，不需要再操作。']
      ],
      reason: rules?.allow_defer_one_round === false
        ? '系统规则不允许暂缓一次。'
        : unavailableReason('defer')
    },
    {
      mode: 'skip_one_turn',
      title: '轮空一次',
      summary: '轮到时移至队尾',
      facts: [
        ['适用', '需要晚些再游玩，在重新排一次队的时间内可以回来。'],
        ['轮到时', '下一次轮到时移至队尾，计一次轮空；本轮由其余在场的登记重新组合。'],
        ['结束', '移至队尾后自动解除，不需要再操作；轮空次数与暂时离开合并计算。']
      ],
      reason: unavailableReason('skip_one_turn')
    },
    {
      mode: 'temporary_leave',
      title: '暂时离开',
      summary: '离开期间轮空，返回后取消',
      facts: [
        ['适用', '需要离开现场一段时间。'],
        ['轮到时', '每次轮到都移至队尾，计一次轮空；离开期间不参与游玩组合。'],
        ['结束', '返回后手动取消暂时离开；第 4 次轮到时，登记会被自动移除。']
      ],
      reason: rules?.allow_temporary_leave === false
        ? '系统规则不允许暂时离开。'
        : unavailableReason('temporary_leave')
    }
  ]
}

// The confirmation after a card. Null for any other mode. `limitNotice` is what the terminal
// says, between the two texts, about the turns the registration has already missed.
export function postponeConfirmation(registration, mode) {
  if (!registration) return null
  const pair = registration.fixed_pair === true
  const subject = pair ? '这组登记' : `“${registration.display_id}”`
  const skippedTurns = registration.temporary_away_skipped_turns || 0
  if (mode === 'defer') {
    return {
      title: pair ? '确认整组暂缓一次？' : '确认暂缓一次？',
      detail: pair
        ? `下一次轮到${subject}时，整组不会进入游玩位置。两份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。`
        : `下一次轮到${subject}时不会进入游玩位置。这份登记保持当前顺序，并在跳过这次机会后自动解除暂缓。`,
      note: '暂缓的登记在跳过的这一轮不占用共同游玩位置；系统会按照其余在场登记的游玩偏好重新组合。',
      confirm: '确认暂缓一次',
      operation: 'DEFER_ONE_ROUND'
    }
  }
  if (mode === 'skip_one_turn') {
    return {
      title: pair ? '确认整组轮空一次？' : '确认轮空一次？',
      detail: pair
        ? `下一次轮到${subject}时，整组不会进入游玩位置，而会一起按一次轮空移至当前队尾；轮空一次随即自动解除。`
        : `下一次轮到${subject}时不会进入游玩位置，而会按一次轮空移至当前队尾；轮空一次随即自动解除。`,
      limitNotice: skipOneTurnLimitNotice(skippedTurns, subject),
      note: '轮空的登记不会占用共同游玩位置，系统会按照其余在场登记的游玩偏好重新组合。轮到之前，可以从登记菜单取消轮空一次。',
      confirm: '确认轮空一次',
      operation: 'SKIP_ONE_TURN'
    }
  }
  if (mode === 'temporary_leave') {
    return {
      title: pair ? '确认整组暂时离开？' : '确认暂时离开？',
      detail: `下一次轮到${subject}时不会进入游玩位置，而会按一次轮空移至当前队尾；之后每次轮到时仍会移至队尾。`,
      limitNotice: temporaryLeaveRetainedTurnsNotice(skippedTurns, subject),
      note: '暂时离开的登记不会占用共同游玩位置，系统会按照其余在场登记的游玩偏好重新组合。玩家返回后，需要从登记菜单手动取消暂时离开。累计轮空 3 次后，第 4 次轮到时仍未取消，登记会自动移除；已轮空次数会在游玩一轮后清零。',
      confirm: '确认暂时离开',
      operation: 'TEMPORARILY_LEAVE'
    }
  }
  return null
}

// Cancelling a postponement takes effect at once, without a confirmation, as on the terminal.
export const POSTPONEMENT_CANCEL_OPERATIONS = {
  cancel_defer: 'CANCEL_DEFER_ONE_ROUND',
  cancel_skip_one_turn: 'CANCEL_SKIP_ONE_TURN',
  cancel_temporary_leave: 'CANCEL_TEMPORARY_LEAVE'
}

// The label of the entry for moving machines: the machine's name when there is only one other.
export function transferEntryLabel(otherMachines) {
  return otherMachines?.length === 1 ? `转至 ${otherMachines[0].name}` : '转至其他机台'
}

// Why [registration] cannot move to [machine] (a machine as the account API lists it), or an
// empty string. The sentences and their order are the terminal's
// (machineTransferUnavailableReason); the server refuses such a move as well. From its own menu
// one registration moves, so a machine with 20 registrations cannot take it.
export function transferTargetUnavailableReason(registration, machine) {
  if (!registration || !machine) return ''
  if (machine.operational === false) return `${machine.name} 已停止使用，暂不能转入。`
  if (machine.capacity === 1 && registration.fixed_pair) {
    return `${machine.name} 仅能容纳一人游玩，请先解除固定组合再转入。`
  }
  if ((machine.registration_count ?? 0) >= 20) {
    return `${machine.name} 的登记将超过 20 份，无法接收这份登记。`
  }
  return ''
}

// Why the entry for moving machines is grey, as on the terminal, where the entry is always shown
// outside the playing position: with no other machine there is nowhere to go; with one other
// machine the entry leads straight to its confirmation, so that machine's reason is told before;
// with several, only when none of them can take the registration.
export function transferEntryUnavailableReason(registration, otherMachines) {
  if (!registration) return ''
  if (!otherMachines?.length) return '当前没有其他已配置的机台。'
  if (otherMachines.length === 1) {
    return transferTargetUnavailableReason(registration, otherMachines[0])
  }
  const anyTakes = otherMachines.some((machine) => (
    machine.available !== false && !transferTargetUnavailableReason(registration, machine)
  ))
  return anyTakes ? '' : '其他机台当前都不能接收这份登记。'
}

// The rows of 选择要转至的机台, as the terminal lists them: what waits at each machine and the
// wait of a registration that joins its tail, or, for a machine that cannot take the
// registration, the reason in a grey row. `waitingPositionCounts` gives the number of waiting
// positions by machine id as the queue page knows it; the account API does not report it, and a
// machine without one shows its registrations only.
export const TRANSFER_CHOOSER_TITLE = '选择要转至的机台'
export const TRANSFER_WAIT_LABEL = '转入后预计等待'

export function transferChooserRows(registration, otherMachines, waitingPositionCounts = {}) {
  return (otherMachines || []).map((machine) => {
    const reason = transferTargetUnavailableReason(registration, machine)
    return {
      id: machine.id,
      name: machine.name,
      reason,
      description: reason || machineQueueSummary({
        registrationCount: machine.registration_count ?? 0,
        waitingPositionCount: waitingPositionCounts?.[machine.id],
        emptyText: '当前没有登记。'
      }),
      wait: reason ? '' : newRegistrationWaitText(machine.estimated_wait_minutes)
    }
  })
}

// Under the rows for a member of a fixed pair, where some machine holds one player only.
export const TRANSFER_FIXED_PAIR_NOTE =
  '仅能容纳一人游玩的机台不能接收固定组合；如需转至这类机台，请先更改游玩偏好，解除固定组合。'

// 更改游玩偏好, 转至 and 退出排队 in the terminal's words (maintainer, 2026-11-25: “网页版的原则就是
// 尽量和终端保持UX一致”). Where the terminal names the other player of a fixed pair, the website,
// which knows only its own registration, says 另一份登记. One difference is kept on purpose: the
// terminal applies a play preference as soon as it is tapped, while the website asks to confirm,
// because the change goes to the terminal as one or two remote commands and the tick box's first
// explanation has to stay on screen.

function registrationName(registration) {
  return `“${registration.display_id || registration.displayId}”`
}

// What a fixed pair keeps of 暂缓一次, 轮空一次 or 暂时离开 when it is released (terminal:
// fixedPairAbsenceRetentionNotice).
function fixedPairAbsenceRetention(registration) {
  if (registration.deferred_once) return '两份登记的暂缓一次不会因解除固定组合而取消。'
  if (registration.skips_one_turn) return '两份登记的轮空一次不会因解除固定组合而取消。'
  if (registration.temporarily_away) {
    return `两份登记当前的暂时离开状态和已轮空 ${registration.temporary_away_skipped_turns || 0} 次不会因解除组合而清除。`
  }
  return ''
}

// What the other registration of a fixed pair keeps when this one leaves (terminal:
// remainingPartnerAbsenceNotice). The pair shares its state, so this registration's is the
// partner's.
function remainingPartnerAbsence(registration) {
  if (registration.deferred_once) return '另一份登记仍将暂缓一次，并会在下一次轮到后自动解除。'
  if (registration.skips_one_turn) return '另一份登记仍将轮空一次，并会在下一次轮到时移至队尾。'
  if (registration.temporarily_away) {
    return `另一份登记仍保持暂时离开和已轮空 ${registration.temporary_away_skipped_turns || 0} 次，返回后需要手动取消。`
  }
  return ''
}

// How a member of a fixed pair reads in place of its play preference, as on the terminal's
// registration menu. `partnerName` is the other registration of the pair where the page knows
// it; without it the terminal's label of a fixed pair stands.
export function fixedPairStateText(partnerName) {
  return partnerName ? `与“${partnerName}”共同游玩` : '与朋友共同游玩'
}

// The second question when a member of a fixed pair has chosen a play preference in
// 更改游玩偏好, as on the terminal (ReleaseFixedPairConfirmation and
// releaseFixedPairConfirmationText): this registration takes the chosen preference, the other
// one becomes 允许他人加入. In the playing position the round goes on as it is. `partnerName` is
// the other registration's name where the page knows it; otherwise it reads 另一份登记.
export function releaseFixedPairConfirmation(registration, preference, partnerName = null) {
  if (!registration) return null
  const own = registrationName(registration)
  const partner = partnerName ? `“${partnerName}”` : '另一份登记'
  const preferences = preference === 'OPEN_TO_JOIN'
    ? '两份登记的游玩偏好都会改为允许他人加入'
    : `${own}的游玩偏好会改为单人游玩，${partner}的游玩偏好会改为允许他人加入`
  const effect = registration.position === 'PLAYING'
    ? '本轮游玩结束后生效'
    : '系统会按照等待顺序重新组成等待位置'
  return {
    title: '解除固定组合？',
    detail: `确认后，${own}和${partner}的固定组合会解除。${preferences}，${effect}。${fixedPairAbsenceRetention(registration)}`,
    confirm: '确认解除固定组合'
  }
}

// Whether confirming 更改游玩偏好 with [preference] releases the fixed pair of [registration],
// so that the second question comes first.
export function preferenceChangeReleasesFixedPair(registration, preference) {
  return registration?.fixed_pair === true && registration.machine_capacity > 1 &&
    PREFERENCE_OPTIONS.some((option) => option.value === preference)
}

export function preferenceChooserText(registration) {
  return {
    title: '更改游玩偏好',
    // In the playing position nothing changes this round, which can surprise the player; worded
    // like the tick box for two machines, as on the terminal (maintainer, 2026-11-26).
    intro: registration?.position === 'PLAYING'
      ? '新的选择只用于本次排队，本轮游玩结束后生效；玩家资料中的默认游玩偏好不会改变。'
      : '新的选择只用于本次排队，并会用于重新划分等待位置；玩家资料中的默认游玩偏好不会改变。',
    fixedPairNote: registration?.fixed_pair
      ? `当前属于固定组合。改为单人游玩或允许他人加入时，双方的固定组合会同时解除。${fixedPairAbsenceRetention(registration)}`
      : '',
    singleMachineNote: registration?.machine_capacity === 1
      ? '当前机台仅能容纳一人游玩，这份登记使用“单人游玩”。'
      : ''
  }
}

// The option marked when 更改游玩偏好 opens. A member of a fixed pair has neither: its state is
// the fixed pair, which fixedPairNote names, and choosing either option releases the pair. As on
// the terminal, where neither row is marked for a fixed pair.
export function initialPreferenceChoice(registration) {
  return registration?.fixed_pair ? '' : registration?.preference || ''
}

// Where the two passing tick boxes start when 更改游玩偏好 opens. allows_passing of a member of a
// fixed pair is the pair's choice, shown in the pair's box; the box inside 单人游玩 is then off,
// because the pair's choice ends when a chosen preference releases the pair.
export function initialPassingChoices(registration) {
  const allowsPassing = registration?.allows_passing === true
  const fixedPair = registration?.fixed_pair === true
  return { passing: allowsPassing && !fixedPair, pairPassing: allowsPassing && fixedPair }
}

// Beside 允许他人加入 where the play preference of a new registration is chosen, as on the
// terminal's join step; not in 更改游玩偏好.
export const PREFERENCE_RECOMMENDED_BADGE = '推荐'

export const PREFERENCE_OPTIONS = [
  { value: 'SOLO', title: '单人游玩', description: '这份登记将独自占用一个等待位置。' },
  {
    value: 'OPEN_TO_JOIN',
    title: '允许他人加入',
    description: '与其他允许他人加入的登记组成共同游玩，通常可以缩短等待时间；每轮最多两人。'
  }
]

// Whether the fixed pair's tick box in 更改游玩偏好 differs from the pair's state. It counts only
// while the member has chosen no preference: choosing one releases the pair, and the pair's
// choice ends with it. `pairPassing` is null where the panel does not offer the box.
export function fixedPairPassingChanged({ registration, preference, pairPassing = null }) {
  if (!registration || pairPassing === null || registration.fixed_pair !== true) return false
  if (PREFERENCE_OPTIONS.some((option) => option.value === preference)) return false
  return pairPassing !== (registration.allows_passing === true)
}

// The commands of one 更改游玩偏好 confirmation, in the order the terminal receives them: the
// play preference, the tick box that lets others pass, and the tick box for the two machines of
// a pair, as a list of { operation, extra }. Each is sent only after the terminal has applied
// the one before, against the state it left behind (queueCommandChain.js).
// `pairedTick`, `passing` and `pairPassing` are null where the panel does not offer that tick
// box. Choosing a preference other than 单人游玩 ends passing on the terminal by itself, so the
// passing tick box adds no command then. With nothing changed the preference is sent again, as
// before. Without a chosen preference, as for a member of a fixed pair that has not chosen one,
// nothing is sent about the preference; a preference chosen by such a member always counts as a
// change.
// A member of a fixed pair that changes only the pair's tick box (`pairPassing`) sends
// SET_ALLOWS_PASSING and nothing about the preference: the terminal applies it to both
// registrations and the pair stays. Such a command is marked `fixedPair` for the wording of its
// outcome. A member that chooses a preference releases the pair, which ends the pair's choice
// on the terminal; the tick box inside 单人游玩 then starts from off.
export function preferenceChangeCommands({
  registration,
  preference,
  pairedTick = null,
  passing = null,
  pairPassing = null
}) {
  if (!registration) return []
  const preferenceChosen = PREFERENCE_OPTIONS.some((option) => option.value === preference)
  const choosesPreference = registration.machine_capacity > 1 && preferenceChosen
  const nextPreference = choosesPreference ? preference : registration.preference
  const preferenceChanged = choosesPreference &&
    (preference !== registration.preference || registration.fixed_pair === true)
  const ownPassing = registration.fixed_pair !== true && registration.allows_passing === true
  const passingChanged = passing !== null && nextPreference === 'SOLO' &&
    (registration.fixed_pair !== true || choosesPreference) && passing !== ownPassing
  const pairPassingChanged = registration.machine_capacity > 1 &&
    fixedPairPassingChanged({ registration, preference, pairPassing })
  const pairedChanged = pairedTick !== null &&
    pairedTick !== (registration.also_queues_at_paired_machine === true)
  const commands = []
  if (preferenceChanged || (preferenceChosen && !passingChanged && !pairedChanged)) {
    commands.push({ operation: 'CHANGE_PLAY_PREFERENCE', extra: { preference } })
  }
  if (passingChanged) {
    commands.push({ operation: 'SET_ALLOWS_PASSING', extra: { allows_passing: passing } })
  }
  if (pairPassingChanged) {
    commands.push({
      operation: 'SET_ALLOWS_PASSING',
      extra: { allows_passing: pairPassing },
      fixedPair: true
    })
  }
  if (pairedChanged) {
    commands.push({
      operation: 'SET_PAIRED_MACHINE_QUEUEING',
      extra: { also_queues_at_paired_machine: pairedTick }
    })
  }
  return commands
}

// `pairedNotice` is what a transfer by hand means for a ticked registration (pairedQueueCopy.js),
// or an empty string.
export function transferConfirmation(registration, target, pairedNotice = '') {
  if (!registration || !target) return null
  const details = [`确认后，${registrationName(registration)}会从 ${registration.machine_name} 移出，并加入 ${target.name} 的队尾。`]
  if (registration.fixed_pair) {
    details.push(`这份登记属于固定组合。只将其中一份转至 ${target.name} 会解除固定组合；转入的登记和留在原机台的登记都会恢复为允许他人加入。`)
  }
  if (target.capacity === 1) {
    details.push(`${target.name} 仅能容纳一人游玩。转入后，这份登记会使用“单人游玩”；玩家资料中的默认游玩偏好不会改变。之后转回支持共同游玩的机台时，这份登记仍会保持“单人游玩”；需要时，请手动更改本次游玩偏好。`)
  }
  if (pairedNotice) details.push(pairedNotice)
  if (registration.temporarily_away) {
    const skipped = registration.temporary_away_skipped_turns || 0
    details.push(registration.fixed_pair
      ? `两份登记的暂时离开状态和已轮空 ${skipped} 次都会保留；转入后仍需手动取消。`
      : `暂时离开状态和已轮空 ${skipped} 次会保留；转入后仍需手动取消。`)
  } else if (registration.skips_one_turn) {
    // Arriving at the tail already does what 轮空一次 would.
    details.push(registration.fixed_pair
      ? '转入登记已在队尾，不再轮空；留在原机台的登记仍将轮空一次。'
      : '转入后已在队尾，不再轮空。')
  } else if (registration.deferred_once) {
    details.push(registration.fixed_pair
      ? '转入登记不再暂缓；留在原机台的登记仍将暂缓一次。'
      : '转入后不再暂缓。')
  }
  return {
    title: `转至 ${target.name}？`,
    details,
    warning: `原机台上的当前位置不会保留。即使之后再次转回 ${registration.machine_name}，也只能加入转回时的队尾。转入后，等待位置会按照 ${target.name} 的等待顺序和游玩偏好重新划分。`,
    confirm: `确认转至 ${target.name}`
  }
}

export function leaveConfirmation(registration) {
  if (!registration) return null
  const name = registrationName(registration)
  let detail = `${name}退出后，这份登记将立即失效。`
  if (registration.fixed_pair) {
    detail += `固定组合也会解除；另一份登记会保留在原位置，并恢复为允许他人加入。${remainingPartnerAbsence(registration)}`
  }
  detail += '如需继续游玩，需要重新加入队尾。'
  return {
    title: '退出排队？',
    detail,
    // Somebody away from the venue cannot see the playing position; the terminal's players can.
    note: registration.position === 'PLAYING'
      ? '游玩位置中的空缺不会自动由等待顺序中的下一组登记补入。'
      : '',
    confirm: '确认退出',
    operation: 'LEAVE_QUEUE',
    danger: true
  }
}

// 与朋友共同游玩 with a registration that already waits at the same machine (2026-11-28), in the
// terminal's words (FriendPairFlowDialog and the row under 也可以 in 更改游玩偏好). The terminal
// also creates a registration for the friend; online that registration would still have to
// check in and could not be paired, so only the existing registration is offered. The terminal
// works out how the order changes before it asks to confirm; the website cannot, so it says the
// general rule and shows what the terminal answers.
//
// `pageRegistrations` are the registrations of the public queue as the status page shows them:
// { registrationId, displayId, machineId, waiting, positionLabel, preference, fixedPair,
// pendingCheckIn, deferredOnce, skipsOneTurn, temporarilyAway }. The account's own queue API
// lists only the player's registrations.

export const FRIEND_PAIR_SECTION_LABEL = '也可以'
export const FRIEND_PAIR_ENTRY_TITLE = '与朋友共同游玩'
const FRIEND_PAIR_ENTRY_DESCRIPTION = '与朋友组成固定组合，不会延后其他登记应当取得的游玩机会。'
const FRIEND_PAIR_PLAYING_POSITION_REASON =
  '这份登记处于游玩位置。请在本轮结束、这份登记回到等待顺序后，再组成固定组合。'
const FRIEND_PAIR_NO_CANDIDATE_REASON = '当前没有可用于组成固定组合的其他等待登记。'
const FRIEND_PAIR_PENDING_CHECK_IN_REASON =
  '这份线上登记尚未完成现场签到，签到后才能组成固定组合。'

export const FRIEND_PAIR_TEXT = {
  selectTitle: '选择朋友的登记',
  selectIntro: '系统会以较靠后的登记为基准安排固定组合，并先检查是否有其他玩家因此延后。',
  confirmTitle: FRIEND_PAIR_ENTRY_TITLE,
  rule: '组成固定组合时，两份登记中较靠前的一份会后移，不会延后其他玩家。',
  note: '固定组合建立后，系统不会再为这两份登记安排其他玩家共同游玩。为保持原有游玩轮次，其他允许他人加入的登记可能重新组合；游玩轮次没有推迟的登记不视为延后。',
  consent: '双方已经明确同意组成固定组合。',
  consentMissing: '请先确认两位玩家都同意组成固定组合。',
  confirm: '确认组成固定组合',
  changed: '等待顺序或登记状态已经发生变化，请返回后重新选择朋友。'
}

// Why a postponed registration cannot form a fixed pair (terminal: fixedPairPostponedReason);
// [ending] is 与朋友共同游玩 for the registration that starts and 组成固定组合 for the friend's.
// [state] reads either spelling of the three fields.
function fixedPairPostponedReason(state, ending) {
  if (state.deferred_once || state.deferredOnce) return `这份登记将暂缓一次，取消后才能${ending}。`
  if (state.skips_one_turn || state.skipsOneTurn) return `这份登记将轮空一次，取消后才能${ending}。`
  if (state.temporarily_away || state.temporarilyAway) {
    return `这份登记处于暂时离开状态，取消后才能${ending}。`
  }
  return ''
}

// The rows of 选择朋友的登记 for [registration] (as the account API reports it): the other
// registrations that wait at its machine and belong to no fixed pair, in the page's order. A row
// names the registration, then its position and play preference, or, greyed, why it cannot be
// chosen.
export function friendPairCandidateRows(registration, pageRegistrations) {
  if (!registration || registration.position !== 'WAITING') return []
  return (pageRegistrations || [])
    .filter((candidate) => (
      candidate.registrationId &&
      candidate.registrationId !== registration.registration_id &&
      candidate.machineId === registration.machine_id &&
      candidate.waiting && !candidate.fixedPair
    ))
    .map((candidate) => {
      const reason = candidate.pendingCheckIn
        ? FRIEND_PAIR_PENDING_CHECK_IN_REASON
        : fixedPairPostponedReason(candidate, '组成固定组合')
      const preference = candidate.preference === 'SOLO' ? '单人游玩' : '允许他人加入'
      return {
        id: candidate.registrationId,
        name: candidate.displayId,
        reason,
        description: reason || `${candidate.positionLabel || '等待顺序'}·${preference}。`
      }
    })
}

// Whether 更改游玩偏好 offers 与朋友共同游玩 for [registration]: a registration outside a fixed
// pair on a machine for two, where the terminal has declared the operation.
export function friendPairOffered(registration, queueOperations) {
  return Boolean(registration) && registration.fixed_pair !== true &&
    registration.machine_capacity > 1 &&
    Array.isArray(queueOperations) && queueOperations.includes('CREATE_FIXED_PAIR')
}

// The row under 也可以: its description, or, greyed, why it cannot be used right now. The
// terminal greys the row while the own registration is postponed. Its next page says why a
// registration in the playing position has to wait, and greys 选择现有登记 when nobody can be
// chosen; the website opens the list at once, so both stand on the row instead.
export function friendPairEntry(registration, pageRegistrations) {
  const reason = !registration
    ? ''
    : fixedPairPostponedReason(registration, FRIEND_PAIR_ENTRY_TITLE) ||
      (registration.position === 'PLAYING' ? FRIEND_PAIR_PLAYING_POSITION_REASON : '') ||
      (friendPairCandidateRows(registration, pageRegistrations).length
        ? ''
        : FRIEND_PAIR_NO_CANDIDATE_REASON)
  return {
    title: FRIEND_PAIR_ENTRY_TITLE,
    description: reason || FRIEND_PAIR_ENTRY_DESCRIPTION,
    reason
  }
}

// 修改登记昵称 (2026-11-28), in the terminal's words: the name this one registration shows, not
// the profile's. The terminal's pencil asks first whether the registration or the profile is
// meant; the website edits the profile under 资料设置, so its pencil opens this at once.
export const REGISTRATION_RENAME_ENTRY_LABEL = '编辑登记昵称'
export const REGISTRATION_RENAME_MAX_LENGTH = 18
export const REGISTRATION_RENAME_TEXT = {
  title: '修改登记昵称',
  intro: '只修改这份登记显示的昵称，不会修改玩家资料。昵称应当能够让现场玩家确认这份登记对应的人。',
  label: '昵称',
  placeholder: '例如：Rin',
  hint: '请使用现场玩家能够认出的昵称。',
  takenHint: '这个昵称已有一份有效登记。',
  missing: '请先填写新的登记昵称。',
  taken: '当前队列中已经有相同昵称的登记。',
  save: '保存昵称'
}

// Whether the pencil is shown: the terminal has declared the operation, and the registration
// does not still have to check in.
export function registrationRenameOffered(registration, queueOperations) {
  return Boolean(registration) && registration.online_registration_pending_check_in !== true &&
    Array.isArray(queueOperations) && queueOperations.includes('RENAME_REGISTRATION')
}

// A nickname as it is typed: at most 18 characters, counted as the terminal counts them.
export function limitRegistrationName(value) {
  return Array.from(String(value || '')).slice(0, REGISTRATION_RENAME_MAX_LENGTH).join('')
}

// Whether another registration of the queue already shows [name], at any machine, compared as
// the terminal compares nicknames.
export function registrationNameTaken(name, registration, pageRegistrations) {
  const wanted = String(name || '').trim().toLowerCase()
  if (!wanted) return false
  return (pageRegistrations || []).some((candidate) => (
    candidate.registrationId !== registration?.registration_id &&
    String(candidate.displayId || '').trim().toLowerCase() === wanted
  ))
}

// 将本次选择保存为默认游玩偏好，以后不再询问。 (2026-11-28): the tick row under the play preference
// while joining, where the terminal offers it: the profile asks every time and the machine takes
// two players. The terminal that reads the choice is the one that declares RENAME_REGISTRATION
// (both came with the same version); an older terminal would ignore the tick, so the row is
// not shown for it.
export const REMEMBER_PREFERENCE_LABEL = '将本次选择保存为默认游玩偏好，以后不再询问。'

export function rememberPreferenceOffered(asksPreference, queueOperations) {
  return asksPreference === true &&
    Array.isArray(queueOperations) && queueOperations.includes('RENAME_REGISTRATION')
}

// Among the facts of a registration, as in the terminal's registration menu.
export const ONLINE_REGISTRATION_CHECKED_IN_FACT = '线上登记，已签到'
