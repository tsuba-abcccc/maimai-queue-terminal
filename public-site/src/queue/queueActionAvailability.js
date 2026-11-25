// Which remote action on a player's own registration can be carried out right now, and the
// sentence shown when one cannot. The rules are those of the terminal and the server; the
// account dialog and the registration details of the status page share them word for word.

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

// What 轮空一次 does, where the account dialog and the status page explain the choices.
export const SKIP_ONE_TURN_EXPLANATION =
  '下一次轮到时移至队尾，计一次轮空，随后自动解除。'

// 轮空一次 shares the count of skipped turns with 暂时离开, and three are the limit. Few skip three
// times in a row, so the limit is only stressed once it is near: when this skip will be the third,
// and when the next turn removes the registration. The same sentences as on the terminal. The
// count is known only once 轮空一次 is set, so this follows the state, not the confirmation.
export function skipOneTurnLimitNotice(skippedTurns) {
  if (skippedTurns >= 3) {
    return `这份登记已轮空 ${skippedTurns} 次。下一次轮到时，登记会被自动移除，而不会移至队尾。`
  }
  if (skippedTurns === 2) {
    return '这份登记已轮空 2 次，这次将是第 3 次。此后再轮到时，如果仍设置了轮空一次或暂时离开，登记会被自动移除。'
  }
  return ''
}

// `registration` as the account API reports it; `mode` as in QUEUE_ACTION_MODE_BY_OPERATION.
// Returns an empty string when the action can be sent.
export function queueActionUnavailableReason({ terminalOnline, remoteActions, registration, mode }) {
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
    return '现场未开启网站远程操作，当前只能查看状态。'
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
        intro: '暂时不能游玩时，选择一种方式。三种方式的区别如下。'
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

// The confirmation after a card. Null for any other mode.
export function postponeConfirmation(registration, mode) {
  if (!registration) return null
  const pair = registration.fixed_pair === true
  const subject = pair ? '这组登记' : `“${registration.display_id}”`
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
      note: '轮空的登记不会占用共同游玩位置，系统会按照其余在场登记的游玩偏好重新组合。轮到之前，可以从登记菜单取消轮空一次。',
      confirm: '确认轮空一次',
      operation: 'SKIP_ONE_TURN'
    }
  }
  if (mode === 'temporary_leave') {
    return {
      title: pair ? '确认整组暂时离开？' : '确认暂时离开？',
      detail: `下一次轮到${subject}时不会进入游玩位置，而会按一次轮空移至当前队尾；之后每次轮到时仍会移至队尾。`,
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

export function preferenceChooserText(registration) {
  return {
    title: '更改游玩偏好',
    intro: '新的选择只用于本次排队，并会用于重新划分等待位置；玩家资料中的默认游玩偏好不会改变。',
    fixedPairNote: registration?.fixed_pair
      ? `当前属于固定组合。改为单人游玩或允许他人加入时，双方的固定组合会同时解除。${fixedPairAbsenceRetention(registration)}`
      : '',
    singleMachineNote: registration?.machine_capacity === 1
      ? '当前机台仅能容纳一人游玩，这份登记使用“单人游玩”。'
      : ''
  }
}

export const PREFERENCE_OPTIONS = [
  { value: 'SOLO', title: '单人游玩', description: '这份登记将独自占用一个等待位置。' },
  {
    value: 'OPEN_TO_JOIN',
    title: '允许他人加入',
    description: '与其他允许他人加入的登记组成共同游玩，通常可以缩短等待时间；每轮最多两人。'
  }
]

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
