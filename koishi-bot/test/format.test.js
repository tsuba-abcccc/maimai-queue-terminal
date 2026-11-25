const test = require('node:test')
const assert = require('node:assert/strict')

const {
  HELP_TEXT,
  absenceOperationSuccessMessage,
  changeAbsenceState,
  formatMachineChoice,
  formatMachineChoiceLines,
  formatMachineReplyHint,
  formatClientVersions,
  formatRegistrationCount,
  formatOwnQueue,
  formatOwnQueueActions,
  formatNotificationQueueStatus,
  machineCanAcceptRegistration,
  onlineRegistrationProfileCompletionNotice,
  formatQueue,
  formatQueueMessages,
  formatQueueNotification,
  joinQueueFromBot,
  leaveQueueFromBot,
  nicknameValidationError,
  parseGender,
  parseMachineChoice,
  parsePlayPreference,
  parsePreference,
  parseNotificationPreference,
  queueConfirmationContextFields,
  transferQueueMachine,
} = require('../lib')

test('locks the Bot confirmation to the registration state shown before the reply', () => {
  const context = queueConfirmationContextFields('queue-1', {
    registration_id: 'registration-1',
    machine_id: 'B',
    position: 'WAITING',
    fixed_pair: true,
    fixed_pair_id: 'pair-1',
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    online_registration_pending_check_in: false,
  }, 17)

  assert.deepEqual(context, {
    expected_queue_id: 'queue-1',
    expected_registration_id: 'registration-1',
    expected_machine_id: 'B',
    expected_position: 'WAITING',
    expected_fixed_pair_id: 'pair-1',
    expected_absence_status: 'TEMPORARILY_AWAY',
    expected_temporary_away_skipped_turns: 2,
    expected_pending_check_in: false,
    expected_machine_configuration_revision: 17,
  })
})

test('keeps queue operations compatible when an older server omits the machine revision', () => {
  const context = queueConfirmationContextFields('queue-1', {
    registration_id: 'registration-1',
    machine_id: 'A',
    position: 'WAITING',
    fixed_pair: false,
    fixed_pair_id: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    online_registration_pending_check_in: false,
  })

  assert.equal(context.expected_machine_configuration_revision, undefined)
  assert.equal(context.expected_queue_id, 'queue-1')
  assert.equal(context.expected_registration_id, 'registration-1')
})

test('explains the extra on-site step for legacy online-registration profiles', () => {
  const notice = onlineRegistrationProfileCompletionNotice(0)

  assert.match(notice, /先在终端补全资料/)
  assert.match(notice, /再点击“已到场”完成签到/)
  assert.equal(onlineRegistrationProfileCompletionNotice(1), null)
})

test('help text uses a message-safe profile menu', () => {
  assert.doesNotMatch(HELP_TEXT, /[<>]/)
  assert.match(HELP_TEXT, /修改资料/)
  assert.match(HELP_TEXT, /加入排队/)
  assert.match(HELP_TEXT, /排队通知/)
  assert.match(HELP_TEXT, /设置 QQ 后才能使用/)
  assert.ok(HELP_TEXT.indexOf('查询人数') > HELP_TEXT.indexOf('查看队列'))
  assert.ok(HELP_TEXT.indexOf('版本信息') > HELP_TEXT.indexOf('查询人数'))
  assert.ok(HELP_TEXT.indexOf('版本信息') < HELP_TEXT.indexOf('我的资料'))
})

test('formats all client versions without exposing internal status names', () => {
  const message = formatClientVersions({
    checked_at: 1_000,
    components: {
      terminal: {
        name: '现场终端',
        current_version: '0.10.0',
        latest_version: '0.10.1',
        status: 'UPDATE_AVAILABLE',
        updated_at: 900,
      },
      website: {
        name: '队列网站',
        current_version: 'v0.10.1',
        latest_version: '0.10.1',
        status: 'LATEST',
        updated_at: 900,
      },
      bot: {
        name: 'QQ Bot',
        current_version: null,
        latest_version: '0.3.12',
        status: 'UNKNOWN',
        updated_at: null,
      },
    },
  })

  assert.equal(
    message,
    [
      '版本信息',
      '',
      '现场终端·当前 0.10.0·最新 0.10.1',
      '有新版本可用。',
      '最后上报：1970-01-01 08:00',
      '',
      '队列网站·当前 0.10.1·最新 0.10.1',
      '已是最新版本。',
      '最后上报：1970-01-01 08:00',
      '',
      'QQ Bot·当前 未知·最新 0.3.12',
      '暂时无法确认版本状态。',
      '最后上报：尚未上报',
    ].join('\n'),
  )
  assert.doesNotMatch(message, /UPDATE_AVAILABLE|UNKNOWN/)
  assert.doesNotMatch(message, / · |· /)
})

test('formats registration totals and per-machine new-registration estimates', () => {
  const registration = (id) => ({ registration_id: id })
  const queue = {
    registration_open: true,
    terminal: { online: true },
    machines: {
      B: {
        id: 'B',
        name: '右侧国框 · 机台 B',
        operational: true,
        playing: [registration('b1'), registration('b2')],
        waiting_positions: [{ registrations: [registration('b3'), registration('b4')] }],
        new_registration_estimated_wait_minutes: 35,
      },
      A: {
        id: 'A',
        name: '左侧日框 · 机台 A',
        operational: true,
        playing: [registration('a1'), registration('a2')],
        waiting_positions: [{ registrations: [
          registration('a3'), registration('a4'), registration('a5'), registration('a6'),
        ] }],
        new_registration_estimated_wait_minutes: 50,
      },
    },
  }

  assert.equal(
    formatRegistrationCount(queue, 'https://queue.abcccc.top'),
    [
      '当前共 10 个登记。',
      '左侧日框·机台 A 有 6 个登记，新登记估计等待 50 分钟。',
      '右侧国框·机台 B 有 4 个登记，新登记估计等待 35 分钟。',
      '请发送「查看队列」，或者访问 https://queue.abcccc.top，来查看详细的排队状态。',
    ].join('\n\n'),
  )
})

test('does not present a live new-registration estimate when it cannot apply', () => {
  const baseMachine = {
    id: 'A',
    name: '机台 A',
    operational: true,
    playing: [],
    waiting_positions: [],
    new_registration_estimated_wait_minutes: 0,
  }
  const baseQueue = {
    registration_open: true,
    terminal: { online: true },
    machines: { A: baseMachine },
  }
  assert.match(formatRegistrationCount(baseQueue), /新登记预计很快可以游玩/)
  assert.match(
    formatRegistrationCount({ ...baseQueue, registration_open: false }),
    /当前不接收新登记/,
  )
  assert.match(
    formatRegistrationCount({ ...baseQueue, terminal: { online: false } }),
    /最近一次同步数据[\s\S]*新登记等待时间暂时无法估算/,
  )
  assert.match(
    formatRegistrationCount({
      ...baseQueue,
      machines: { A: { ...baseMachine, operational: false } },
    }),
    /机台当前停止使用/,
  )
  assert.match(
    formatRegistrationCount({
      ...baseQueue,
      machines: {
        A: {
          ...baseMachine,
          playing: Array.from({ length: 2 }, (_, index) => ({
            registration_id: `playing-${index}`,
          })),
          waiting_positions: [{
            registrations: Array.from({ length: 18 }, (_, index) => ({
              registration_id: `waiting-${index}`,
            })),
          }],
          new_registration_estimated_wait_minutes: 120,
        },
      },
    }),
    /登记已满/,
  )
})

test('does not present a wait estimate while the current QQ is temporarily away', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 2,
    estimated_wait_minutes: 18,
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 1,
    last_no_show_action_was_defer: true,
  }])

  assert.match(text, /队列位置 A2/)
  assert.match(text, /暂时离开期间无法估算等待时间/)
  assert.doesNotMatch(text, /约 18 分钟后/)
  assert.match(text, /暂时离开·已轮空 2 次/)
  assert.match(text, /未到场记录 1 次/)
  assert.match(text, /未到场记录 1 次·上次处理：暂缓一次/)
})

test('shows move-to-end as the latest no-show handling result', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-2',
    profile_id: 'profile-2',
    qq_number: '87654321',
    display_id: '小林',
    machine_id: 'B',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 2,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /未到场记录 2 次/)
  assert.match(text, /上次处理：移至队尾/)
})

test('describes a zero-minute personal estimate as available soon', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-ready',
    profile_id: 'profile-ready',
    qq_number: '87654321',
    display_id: '即将游玩',
    machine_id: 'B',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 0,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /预计很快可以游玩/)
  assert.doesNotMatch(text, /不足 1 分钟/)
  assert.doesNotMatch(text, /约 0 分钟后/)
})

test('does not put the maintainer site into the default count message', () => {
  const message = formatRegistrationCount({
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '机台 A',
        operational: true,
        playing: [],
        waiting_positions: [],
        new_registration_estimated_wait_minutes: 0,
      },
    },
  })
  assert.doesNotMatch(message, /abcccc\.top/)
})

test('explains a missing estimate from an older personal response', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-without-estimate',
    profile_id: 'profile-without-estimate',
    qq_number: '87654321',
    display_id: '旧数据玩家',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /^你好，旧数据玩家。\n\n你位于队列位置 A1，暂时无法估算。/)
  assert.doesNotMatch(text, /undefined|约 .* 分钟后/)
})

test('shows business-hours state in personal queue output and notifications', () => {
  const player = {
    registration_id: 'registration-closing',
    profile_id: 'profile-closing',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 7,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const personalSnapshot = {
    terminal: { online: true },
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closing_grace: true,
      closes_at: null,
      registration_closes_at: Date.now() + 10 * 60_000,
    },
  }

  const text = formatOwnQueue([player], undefined, personalSnapshot)
  const closingSoonText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
  })

  assert.match(text, /^你好，小雨。\n\n今日营业时间已结束，现有队列正在收尾。\n\n/)
  assert.match(text, /你位于队列位置 A1/)
  assert.match(closingSoonText, /^你好，小雨。\n\n将在 30 分钟内闭店，请留意后续队列安排。\n\n/)

  const offlineClosingSoonText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    terminal: { online: false },
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
  })
  const offlineClosingGraceText = formatOwnQueue([player], undefined, {
    ...personalSnapshot,
    terminal: { online: false },
  })

  assert.doesNotMatch(offlineClosingSoonText, /将在 30 分钟内闭店/)
  assert.match(offlineClosingGraceText, /当前不在营业时间。/)
  assert.doesNotMatch(offlineClosingGraceText, /现有队列正在收尾/)
})

test('formats both machines without exposing private profile fields', () => {
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: false,
        stop_reason: 'NETWORK_DISCONNECTED',
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })
  assert.match(text, /^当前队列·终端在线/m)
  assert.doesNotMatch(text, /maimai Q/i)
  assert.match(text, /^当前队列·终端在线\n\n【入口侧·机台 A】/m)

  assert.match(text, /【入口侧·机台 A】/)
  assert.match(text, /游玩位置 A·空闲/)
  assert.match(text, /【墙侧·机台 B】/)
  assert.match(text, /停止使用·机台断网/)
  assert.match(text, /【入口侧·机台 A】\n\n游玩位置 A·空闲/)
  assert.doesNotMatch(text, /暂无等待登记/)
  assert.match(text, /游玩位置 A·空闲\n\n【墙侧·机台 B】/)
  assert.doesNotMatch(text, /qq_number|profile_id/)
})

test('labels test queue data in queue and personal status messages', () => {
  const queue = {
    queue_id: 'queue-test',
    captured_at: Date.now(),
    registration_open: true,
    test_data: true,
    terminal: { online: true },
    machines: {},
  }

  assert.match(formatQueue(queue), /^当前队列·终端在线\n\n当前数据是测试数据。$/)
  assert.match(
    formatOwnQueue([{
      registration_id: 'registration-1',
      profile_id: 'profile-1',
      display_id: '小雨',
      machine_id: 'A',
      position: 'WAITING',
      position_index: 1,
      online_registration_pending_check_in: true,
    }], queue),
    /\n\n当前数据是测试数据。\n\n/,
  )
})

test('keeps preserved registrations visible while a machine is stopped', () => {
  const registration = {
    registration_id: 'registration-1',
    display_id: '小雨',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 1,
  }
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: false,
        stop_reason: 'NOT_POWERED_ON',
        playing_started_at: null,
        playing: [],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 0,
          registrations: [registration],
        }],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /停止使用·机台未开机/)
  assert.match(text, /登记顺序已保留/)
  assert.match(
    text,
    /位置 A1·机台恢复使用后重新估算\n - 小雨 \(允许加入\)\n    - 暂时离开·已轮空 2 次\n    - 未到场记录 1 次/,
  )
  assert.match(text, /1 个等待位置·1 个登记/)
  assert.doesNotMatch(text, /约 0 分钟后/)
  assert.doesNotMatch(text, /小雨（|允许他人加入/)
})

test('shows fixed pairs and the complete machine stop reason', () => {
  const fixedRegistration = (id, name) => ({
    registration_id: id,
    display_id: name,
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: true,
    no_show_count: 0,
  })
  const machine = (id, overrides = {}) => ({
    id,
    name: `${id === 'A' ? '入口侧' : '墙侧'} · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: [],
    ...overrides,
  })
  const fixedPairText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: machine('A', {
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 0,
          registrations: [
            fixedRegistration('registration-1', '小雨'),
            fixedRegistration('registration-2', '青空'),
          ],
        }],
      }),
      B: machine('B', {
        operational: false,
        stop_reason: 'OTHER',
        stop_reason_detail: '等待配件',
      }),
    },
  })

  assert.match(fixedPairText, /小雨 \(固定组合\)/)
  assert.match(fixedPairText, /青空 \(固定组合\)/)
  assert.match(fixedPairText, /停止使用·其他原因（等待配件）/)

  const maintenanceText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: machine('A'),
      B: machine('B', {
        operational: false,
        stop_reason: 'MAINTENANCE',
      }),
    },
  })
  assert.match(maintenanceText, /停止使用·机台维护/)
})

test('does not show a stale personal estimate while the machine is stopped', () => {
  const player = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    online_registration_pending_check_in: true,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const text = formatOwnQueue([player], {
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: false,
        stop_reason: 'NOT_POWERED_ON',
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·机台未开机，登记顺序已保留/)
  assert.match(text, /机台停止使用期间，30 分钟签到计时暂停；恢复正常使用后会从头开始/)
  assert.doesNotMatch(text, /超过 30 分钟/)
  assert.doesNotMatch(text, /约 8 分钟后/)
})

test('keeps personal status complete without a public queue snapshot', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_name: '入口侧 · 机台 A',
    machine_operational: false,
    machine_stop_reason: 'OTHER',
    machine_stop_reason_detail: '等待配件',
    playing_started_at: null,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    co_player_display_ids: ['青空'],
    preference: 'OPEN_TO_JOIN',
    fixed_pair: true,
    registration_type: 'PLAYER_PROFILE',
    last_played_at: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }], undefined, { terminal: { online: false } })

  assert.match(text, /^你好，小雨。\n\n终端暂时离线/)
  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·其他原因（等待配件），登记顺序已保留。/)
  assert.match(text, /游玩偏好：与朋友共同游玩/)
  assert.match(text, /共同游玩：青空/)
  assert.doesNotMatch(text, /约 8 分钟后/)
  assert.doesNotMatch(text, /允许他人加入/)
})

test('uses the private playing timer when formatting a notification status', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: true,
    playing_started_at: Date.now() - 5 * 60_000,
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    preference: 'SOLO',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])

  assert.match(text, /正在游玩位置 A，已游玩 [45] 分钟/)
  assert.match(text, /游玩偏好：单人游玩/)
})

test('describes a retained playing position as a location while its machine is stopped', () => {
  const player = {
    registration_id: 'registration-stopped-playing',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_name: '入口侧 · 机台 A',
    machine_operational: false,
    machine_stop_reason: 'NETWORK_DISCONNECTED',
    machine_stop_reason_detail: null,
    playing_started_at: Date.now() - 8 * 60_000,
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    preference: 'SOLO',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /位于游玩位置 A/)
  assert.match(text, /机台状态：入口侧·机台 A 已停止使用·机台断网，登记顺序已保留。/)
  assert.doesNotMatch(text, /正在游玩位置/)
  assert.doesNotMatch(text, /已游玩 \d+ 分钟/)
})

test('stops the personal playing timer while the terminal is offline', () => {
  const player = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'PLAYING',
    position_index: null,
    estimated_wait_minutes: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }
  const registration = {
    registration_id: 'registration-1',
    display_id: '小雨',
    preference: 'SOLO',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
  }
  const text = formatOwnQueue([player], {
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: false },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: Date.now() - 5 * 60_000,
        playing: [registration],
        waiting_positions: [],
      },
      B: {
        id: 'B',
        name: '墙侧 · 机台 B',
        operational: true,
        stop_reason: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [],
      },
    },
  })

  assert.match(text, /^你好，小雨。\n\n终端暂时离线/)
  assert.match(text, /位于游玩位置 A/)
  assert.doesNotMatch(text, /正在游玩位置 A/)
  assert.doesNotMatch(text, /已游玩 \d+ 分钟/)
  assert.match(text, /游玩偏好：单人游玩/)
  assert.doesNotMatch(text, /\n\n - (更改游玩偏好|退出排队)/)
})

test('shows the computed business-hours state in the queue header', () => {
  const machines = {
    A: {
      id: 'A',
      name: '入口侧 · 机台 A',
      operational: true,
      stop_reason: null,
      playing_started_at: null,
      playing: [],
      waiting_positions: [],
    },
    B: {
      id: 'B',
      name: '墙侧 · 机台 B',
      operational: true,
      stop_reason: null,
      playing_started_at: null,
      playing: [],
      waiting_positions: [],
    },
  }
  const outside = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closes_at: null,
    },
    terminal: { online: true },
    machines,
  })
  const naturalQueue = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: false,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: false,
      closes_at: Date.now() + 60_000,
    },
    terminal: { online: true },
    machines,
  })

  assert.match(outside, /^当前队列·终端在线·不在营业时间$/m)
  assert.match(naturalQueue, /^当前队列·终端在线·自然排队$/m)
})

test('shows both the thirty-minute closing warning and the closing grace period', () => {
  const emptyMachine = id => ({
    id,
    name: `${id === 'A' ? '入口侧' : '墙侧'} · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: [],
  })
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: true,
      closing_soon: false,
      closing_grace: true,
      closes_at: null,
      registration_closes_at: Date.now() + 20 * 60_000,
    },
    terminal: { online: true },
    machines: { A: emptyMachine('A'), B: emptyMachine('B') },
  })
  const closingSoonText = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 30 * 60_000,
      registration_closes_at: null,
    },
    terminal: { online: true },
    machines: { A: emptyMachine('A'), B: emptyMachine('B') },
  })

  assert.match(text, /^当前队列·终端在线·不在营业时间$/m)
  assert.match(text, /今日营业时间已结束/)
  assert.match(text, /最迟保留 20 分钟/)
  assert.match(closingSoonText, /将在 30 分钟内闭店/)
  assert.match(closingSoonText, /请留意后续队列安排。/)
})

test('queue notifications append only the concise current position', () => {
  const status = formatNotificationQueueStatus([{
    registration_id: 'registration-checked-in',
    profile_id: 'profile-checked-in',
    qq_number: '12345678',
    display_id: '啊波呲',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 10,
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }])
  const message = formatQueueNotification(
    {
      title: '左侧日框 · 机台 A · 线上登记签到状态已更新',
      detail: '“啊波呲”已在现场完成签到。',
    },
    status,
  )

  assert.equal(
    message,
    '【排队通知】\n\n左侧日框·机台 A·线上登记签到状态已更新\n“啊波呲”已在现场完成签到。\n\n现在，你位于队列位置 A1，约 10 分钟后可以游玩。',
  )
  assert.doesNotMatch(message, /\s·|·\s/)
  assert.doesNotMatch(message, /你好|游玩偏好|暂缓一次|退出排队/)
})

test('notification position summaries handle playing and unavailable estimates', () => {
  const base = {
    registration_id: 'registration-status',
    profile_id: 'profile-status',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'B',
    position_index: 2,
    estimated_wait_minutes: null,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  assert.equal(
    formatNotificationQueueStatus([{ ...base, position: 'WAITING' }]),
    '现在，你位于队列位置 B2，暂时无法估算。',
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...base, position: 'PLAYING' }]),
    '现在，你正在游玩位置 B。',
  )
  assert.equal(
    formatNotificationQueueStatus([{
      ...base,
      position: 'WAITING',
      estimated_wait_minutes: 12,
      temporarily_away: true,
    }]),
    '现在，你位于队列位置 B2，暂时离开期间无法估算等待时间。',
  )
  assert.equal(
    formatNotificationQueueStatus([{
      ...base,
      position: 'WAITING',
      estimated_wait_minutes: 12,
      online_registration_pending_check_in: true,
    }]),
    '现在，你位于队列位置 B2，约 12 分钟后可以游玩。',
  )
})

const UNCERTAIN_NOTE = '受系统在两台机台之间安排登记的影响，等待时间可能不准确，请以现场实际情况为准。'

function uncertainEstimateQueue({ online = true, operational = true } = {}) {
  const registration = (id, displayId, fields = {}) => ({
    registration_id: id,
    display_id: displayId,
    preference: 'SOLO',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: false,
    no_show_count: 0,
    ...fields,
  })
  return {
    queue_id: 'queue-uncertain',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online },
    machines: {
      A: {
        id: 'A',
        name: '左侧 · 机台 A',
        operational,
        stop_reason: operational ? null : 'NETWORK_DISCONNECTED',
        stop_reason_detail: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [
          {
            index: 1,
            estimated_wait_minutes: 0,
            estimated_wait_uncertain: true,
            registrations: [registration('registration-1', '甲')],
          },
          {
            index: 2,
            estimated_wait_minutes: 7,
            estimated_wait_uncertain: true,
            registrations: [registration('registration-2', '乙')],
          },
          {
            index: 3,
            estimated_wait_minutes: 19,
            estimated_wait_uncertain: false,
            registrations: [registration('registration-3', '丙')],
          },
          {
            index: 4,
            estimated_wait_minutes: 31,
            registrations: [registration('registration-4', '丁')],
          },
        ],
      },
    },
  }
}

test('writes estimates marked as possibly inaccurate as the earliest time, with the note once', () => {
  const text = formatQueue(uncertainEstimateQueue())

  assert.match(text, /位置 A1·可能很快可以游玩\n/)
  assert.match(text, /位置 A2·最快约 7 分钟后\n/)
  assert.match(text, /位置 A3·约 19 分钟后\n/)
  assert.match(text, /位置 A4·约 31 分钟后\n/)
  assert.equal(text.split(UNCERTAIN_NOTE).length - 1, 1)
  assert.ok(text.endsWith(`\n\n${UNCERTAIN_NOTE}`))

  const offline = formatQueue(uncertainEstimateQueue({ online: false }))
  assert.doesNotMatch(offline, /最快|可能很快/)
  assert.ok(!offline.includes(UNCERTAIN_NOTE))

  const stopped = formatQueue(uncertainEstimateQueue({ operational: false }))
  assert.doesNotMatch(stopped, /最快|可能很快/)
  assert.ok(!stopped.includes(UNCERTAIN_NOTE))

  const away = uncertainEstimateQueue()
  away.machines.A.waiting_positions = away.machines.A.waiting_positions.slice(1, 2)
  away.machines.A.waiting_positions[0].registrations[0].temporarily_away = true
  const awayText = formatQueue(away)
  assert.match(awayText, /位置 A2·暂时离开，无法估算/)
  assert.ok(!awayText.includes(UNCERTAIN_NOTE))

  const certain = uncertainEstimateQueue()
  certain.machines.A.waiting_positions.forEach((position) => {
    position.estimated_wait_uncertain = null
  })
  const certainText = formatQueue(certain)
  assert.match(certainText, /位置 A1·预计很快可以游玩\n/)
  assert.match(certainText, /位置 A2·约 7 分钟后\n/)
  assert.ok(!certainText.includes(UNCERTAIN_NOTE))
})

test('puts the note on estimates marked as possibly inaccurate once at the end of a split queue', () => {
  const queue = uncertainEstimateQueue()
  const complete = formatQueue(queue)
  const messages = formatQueueMessages(queue, complete.length - 1)

  assert.ok(messages.length > 1)
  assert.ok(messages.every((message) => message.length <= complete.length - 1))
  assert.equal(messages.join('\n').split(UNCERTAIN_NOTE).length - 1, 1)
  assert.ok(messages[messages.length - 1].endsWith(UNCERTAIN_NOTE))
})

test('own queue status writes a possibly inaccurate estimate as the earliest time', () => {
  const player = {
    registration_id: 'registration-uncertain',
    profile_id: 'profile-uncertain',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 2,
    estimated_wait_minutes: 6,
    estimated_wait_uncertain: true,
    preference: 'SOLO',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  const text = formatOwnQueue([player])
  assert.match(text, /你位于队列位置 A2，最快约 6 分钟后可以游玩。/)
  assert.match(text, new RegExp(`\n\n${UNCERTAIN_NOTE}\n\n`))

  const soon = formatOwnQueue([{ ...player, estimated_wait_minutes: 0 }])
  assert.match(soon, /你位于队列位置 A2，可能很快可以游玩。/)
  assert.doesNotMatch(soon, /预计很快/)

  const both = formatOwnQueue([
    player,
    { ...player, registration_id: 'registration-other', display_id: '青空', position_index: 3, estimated_wait_minutes: 14 },
  ])
  assert.match(both, /小雨：位于队列位置 A2，最快约 6 分钟后可以游玩。/)
  assert.match(both, /青空：位于队列位置 A3，最快约 14 分钟后可以游玩。/)
  assert.equal(both.split(UNCERTAIN_NOTE).length - 1, 1)

  for (const changed of [
    { estimated_wait_uncertain: false },
    { estimated_wait_uncertain: undefined },
    { estimated_wait_minutes: null },
    { temporarily_away: true },
    { machine_operational: false },
  ]) {
    const unchanged = formatOwnQueue([{ ...player, ...changed }])
    assert.doesNotMatch(unchanged, /最快|可能很快/)
    assert.ok(!unchanged.includes(UNCERTAIN_NOTE))
  }
  assert.match(
    formatOwnQueue([{ ...player, estimated_wait_uncertain: false }]),
    /你位于队列位置 A2，约 6 分钟后可以游玩。/,
  )
})

test('notification status writes a possibly inaccurate estimate as the earliest time', () => {
  const player = {
    registration_id: 'registration-notification',
    profile_id: 'profile-notification',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'B',
    position: 'WAITING',
    position_index: 2,
    estimated_wait_minutes: 12,
    estimated_wait_uncertain: true,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  assert.equal(
    formatNotificationQueueStatus([player]),
    `现在，你位于队列位置 B2，最快约 12 分钟后可以游玩。\n${UNCERTAIN_NOTE}`,
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...player, estimated_wait_minutes: 0 }]),
    `现在，你位于队列位置 B2，可能很快可以游玩。\n${UNCERTAIN_NOTE}`,
  )
  assert.equal(
    formatNotificationQueueStatus([
      player,
      { ...player, display_id: '青空', position_index: 3, estimated_wait_minutes: 20 },
    ]),
    `现在，“小雨”位于队列位置 B2，最快约 12 分钟后可以游玩。\n现在，“青空”位于队列位置 B3，最快约 20 分钟后可以游玩。\n${UNCERTAIN_NOTE}`,
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...player, estimated_wait_uncertain: false }]),
    '现在，你位于队列位置 B2，约 12 分钟后可以游玩。',
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...player, estimated_wait_minutes: null }]),
    '现在，你位于队列位置 B2，暂时无法估算。',
  )
  assert.equal(
    formatNotificationQueueStatus([{ ...player, position: 'PLAYING' }]),
    '现在，你正在游玩位置 B。',
  )
})

test('does not keep advancing public timers or estimates while the terminal is offline', () => {
  const text = formatQueue({
    queue_id: 'queue-offline',
    captured_at: Date.now() - 5 * 60_000,
    registration_open: true,
    business_hours: {
      enabled: true,
      outside: false,
      closing_soon: true,
      closing_grace: false,
      closes_at: Date.now() + 25 * 60_000,
      registration_closes_at: null,
    },
    terminal: { online: false },
    machines: {
      A: {
        id: 'A',
        name: '入口侧 · 机台 A',
        operational: true,
        stop_reason: null,
        playing_started_at: Date.now() - 25 * 60_000,
        playing: [{
          registration_id: 'registration-playing',
          display_id: '小雨',
          preference: 'SOLO',
          deferred_once: false,
          temporarily_away: false,
          temporary_away_skipped_turns: 0,
          no_show_count: 0,
        }],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 9,
          registrations: [{
            registration_id: 'registration-waiting',
            display_id: '青空',
            preference: 'OPEN_TO_JOIN',
            deferred_once: false,
            temporarily_away: false,
            temporary_away_skipped_turns: 0,
            no_show_count: 0,
          }],
        }],
      },
    },
  })

  assert.match(text, /游玩位置 A·状态待更新/)
  assert.match(text, /位置 A1·状态待更新/)
  assert.doesNotMatch(text, /25 分钟|约 9 分钟后/)
  assert.doesNotMatch(text, /将在 30 分钟内闭店/)
})

test('shows the pending check-in state and only allows leaving the queue', () => {
  const player = {
    registration_id: 'registration-online',
    profile_id: 'profile-online',
    qq_number: '12345678',
    display_id: '糍粑',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 4,
    estimated_wait_minutes: 18,
    preference: 'OPEN_TO_JOIN',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: true,
    created_at: 1_000,
    online_check_in_started_at: 1_000,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /^你好，糍粑。/)
  assert.match(text, /你位于队列位置 A4，约 18 分钟后可以游玩。/)
  assert.match(text, /请在创建登记后的 30 分钟内/)
  assert.match(text, /当前状态：线上登记·待签到。/)
  assert.match(text, /\n\n - 退出排队$/)
  assert.doesNotMatch(text, /暂缓一次|暂时离开|切换机台|更改游玩偏好/)
  assert.deepEqual(formatOwnQueueActions(player), ['退出排队'])

  const restartedText = formatOwnQueue([{
    ...player,
    online_check_in_started_at: 2_000,
  }])
  assert.match(restartedText, /机台恢复正常使用后，这份登记已重新获得 30 分钟签到时限/)
  assert.doesNotMatch(restartedText, /请在创建登记后的 30 分钟内/)
})

test('formats pending check-in registrations in the public queue', () => {
  const registration = {
    registration_id: 'registration-online',
    display_id: '糍粑',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: false,
    no_show_count: 0,
    online_registration_pending_check_in: true,
  }
  const machine = id => ({
    id,
    name: `机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: id === 'A'
      ? [{ index: 1, estimated_wait_minutes: 9, registrations: [registration] }]
      : [],
  })
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: { A: machine('A'), B: machine('B') },
  })

  assert.match(text, /位置 A1·约 9 分钟后/)
  assert.match(text, /糍粑 \(允许加入\)\n    - 线上登记·待签到/)
})

test('formats common-play previews without counting them as real registrations', () => {
  const registration = {
    registration_id: 'registration-real',
    display_id: '戊',
    preference: 'OPEN_TO_JOIN',
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    fixed_pair: false,
    no_show_count: 0,
    online_registration_pending_check_in: false,
  }
  const text = formatQueue({
    queue_id: 'queue-preview',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '左侧·机台 A',
        operational: true,
        stop_reason: null,
        stop_reason_detail: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 20,
          registrations: [registration],
          common_play_preview: {
            registration_id: 'registration-returning',
            display_id: '甲',
          },
        }],
      },
    },
  })

  assert.match(text, /1 个等待位置·1 个登记/)
  assert.match(text, / - 戊 \(允许加入\)\n - 甲 \(共同游玩预览\)/)
})

test('distinguishes a projected partner from real group members in personal status', () => {
  const player = {
    registration_id: 'registration-self',
    profile_id: 'profile-self',
    qq_number: '12345678',
    display_id: '戊',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 3,
    estimated_wait_minutes: 20,
    co_player_display_ids: ['乙', '甲'],
    common_play_preview_display_id: '甲',
    preference: 'OPEN_TO_JOIN',
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
  }

  const text = formatOwnQueue([player])

  assert.match(text, /共同游玩：乙。/)
  assert.match(text, /预计与“甲”共同游玩。/)
  assert.doesNotMatch(text, /共同游玩：乙、甲/)
})

test('changes the personal menu with absence state and queue rules', () => {
  const player = {
    position: 'WAITING',
    deferred_once: false,
    temporarily_away: false,
    online_registration_pending_check_in: false,
  }
  assert.deepEqual(formatOwnQueueActions(player, {
    allow_defer_one_round: true,
    allow_temporary_leave: true,
  }), [
    '暂缓一次',
    '轮空一次',
    '暂时离开',
    '切换机台',
    '更改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({ ...player, deferred_once: true }), [
    '取消暂缓一次',
    '切换机台',
    '更改游玩偏好',
    '退出排队',
  ])
  // No queue rule switches 轮空一次 off.
  assert.deepEqual(formatOwnQueueActions(player, {
    allow_defer_one_round: false,
    allow_temporary_leave: false,
  }), ['轮空一次', '切换机台', '更改游玩偏好', '退出排队'])
  // Deferring and leaving temporarily are for registrations that wait.
  assert.deepEqual(formatOwnQueueActions({ ...player, position: 'PLAYING' }), [
    '更改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    position: 'PLAYING',
    temporarily_away: true,
  }), [
    '取消暂时离开',
    '更改游玩偏好',
    '退出排队',
  ])
  // While its machine is stopped a registration can still leave the queue or move away.
  assert.deepEqual(formatOwnQueueActions(player, undefined, false), [
    '切换机台',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    machine_operational: false,
  }), ['切换机台', '退出排队'])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    machine_operational: false,
    deferred_once: true,
  }), ['切换机台', '退出排队'])
  assert.deepEqual(formatOwnQueueActions(player, undefined, false, 2, false), [
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    position: 'PLAYING',
    machine_operational: false,
  }), ['退出排队'])
  assert.deepEqual(formatOwnQueueActions({
    ...player,
    machine_operational: false,
    online_registration_pending_check_in: true,
  }), ['退出排队'])
  assert.deepEqual(formatOwnQueueActions(player, undefined, true, 1), [
    '暂缓一次',
    '轮空一次',
    '暂时离开',
    '切换机台',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions(player, undefined, true, 2, false), [
    '暂缓一次',
    '轮空一次',
    '暂时离开',
    '更改游玩偏好',
    '退出排队',
  ])
})

test('does not offer machine transfer when the queue overview is unavailable', () => {
  const text = formatOwnQueue([{
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: true,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
    preference: 'OPEN_TO_JOIN',
  }], undefined, {
    terminal: { online: true },
    registration_open: true,
  })

  assert.doesNotMatch(text, /切换机台/)
  assert.match(text, /更改游玩偏好/)
  assert.match(text, /退出排队/)
})

test('single-player machine join skips preference prompt and explains the override', async () => {
  const prompts = ['A']
  const sent = []
  let submittedFields
  const session = {
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    send: async message => sent.push(message),
    prompt: async () => prompts.shift() ?? '',
  }
  const api = {
    getProfiles: async () => ({
      profiles: [{
        profile_id: 'profile-1',
        nickname: '小雨',
        default_preference: 'ASK_EVERY_TIME',
        setup_version: 1,
      }],
    }),
    getPlayers: async () => ({ players: [] }),
    getQueue: async () => ({
      queue_id: 'queue-1',
      machine_configuration_revision: 7,
      captured_at: 1,
      registration_open: true,
      onebot_sync_enabled: true,
      queue_rules: { allow_online_registration: true },
      terminal: { online: true },
      machines: {
        A: {
          id: 'A',
          name: '左侧日框 · 机台 A',
          operational: true,
          playing_started_at: null,
          playing: [],
          waiting_positions: [],
          registration_count: 0,
          new_registration_estimated_wait_minutes: 0,
          configuration: {
            game_type: 'MAIMAI_DX',
            server: 'HIDDEN',
            capacity: 1,
            solo_round_minutes: 12,
            shared_round_minutes: 15,
          },
        },
      },
    }),
    createQueueCommand: async (_qq, _operation, fields) => {
      submittedFields = fields
      return {
        command_id: 'command-1',
        status: 'APPLIED',
      }
    },
  }

  const text = await joinQueueFromBot(
    api,
    { commandWaitSeconds: 0 },
    session,
  )

  assert.equal(sent.length, 1)
  assert.equal(prompts.length, 0)
  assert.deepEqual(submittedFields, {
    machine_id: 'A',
    preference: undefined,
    expected_queue_id: 'queue-1',
    expected_machine_configuration_revision: 7,
  })
  assert.match(text, /仅能容纳一人游玩/)
  assert.match(text, /本次已使用“单人游玩”/)
  assert.match(text, /默认游玩偏好不会改变/)
})

test('explains the persistent solo preference before transferring to a single-player machine', async () => {
  const sent = []
  let submittedOperation
  let submittedFields
  const player = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: true,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 8,
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
    preference: 'OPEN_TO_JOIN',
  }
  const api = {
    getPlayers: async () => ({
      queue_id: 'queue-1',
      machine_configuration_revision: 23,
      players: [player],
    }),
    getQueue: async () => ({
      queue_id: 'queue-1',
      captured_at: 1,
      registration_open: true,
      terminal: { online: true },
      machines: {
        A: {
          id: 'A',
          name: '左侧·机台 A',
          operational: true,
          playing: [],
          waiting_positions: [],
          registration_count: 1,
          configuration: { capacity: 2 },
        },
        B: {
          id: 'B',
          name: '右侧·机台 B',
          operational: true,
          playing: [],
          waiting_positions: [],
          registration_count: 0,
          configuration: { capacity: 1 },
        },
      },
    }),
    createQueueCommand: async (_qq, operation, fields) => {
      submittedOperation = operation
      submittedFields = fields
      return { command_id: 'command-1', status: 'APPLIED' }
    },
  }
  const session = {
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    send: async message => sent.push(message),
    prompt: async () => '确认切换机台',
  }

  const text = await transferQueueMachine(
    api,
    { commandWaitSeconds: 0 },
    session,
  )

  assert.equal(submittedOperation, 'TRANSFER_MACHINE')
  assert.equal(submittedFields.target_machine_id, 'B')
  assert.equal(submittedFields.expected_machine_configuration_revision, 23)
  assert.match(sent[0], /本次登记将使用“单人游玩”/)
  assert.match(sent[0], /默认游玩偏好不会改变/)
  assert.match(sent[0], /转回支持共同游玩的机台时，本次登记仍会保持“单人游玩”/)
  assert.match(text, /登记已转至 右侧·机台 B/)
})

test('cancels temporary leave for a fixed pair after the terminal snapshot confirms it', async () => {
  const player = {
    registration_id: 'registration-fixed-player',
    profile_id: 'profile-fixed-player',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: null,
    fixed_pair: true,
    deferred_once: false,
    temporarily_away: true,
    temporary_away_skipped_turns: 2,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
  }
  const responses = [
    {
      queue_id: 'queue-fixed',
      queue_rules: {
        allow_defer_one_round: true,
        allow_temporary_leave: true,
      },
      players: [player],
    },
    {
      queue_id: 'queue-fixed',
      players: [{
        ...player,
        temporarily_away: false,
        temporary_away_skipped_turns: 0,
      }],
    },
  ]
  const submitted = []
  const api = {
    getPlayers: async () => responses.shift() || responses[responses.length - 1],
    createQueueCommand: async (_qq, operation, fields) => {
      submitted.push({ operation, fields })
      return {
        command_id: 'command-fixed',
        status: 'APPLIED',
        result_detail: '固定组合的两份登记已同时取消暂时离开。',
      }
    },
  }

  const text = await changeAbsenceState(
    api,
    { commandWaitSeconds: 3 },
    { platform: 'onebot', userId: '12345678', isDirect: true },
    'CANCEL_TEMPORARY_LEAVE',
  )

  assert.deepEqual(submitted, [{
    operation: 'CANCEL_TEMPORARY_LEAVE',
    fields: {
      expected_queue_id: 'queue-fixed',
      expected_registration_id: 'registration-fixed-player',
      expected_machine_id: 'A',
      expected_position: 'WAITING',
      expected_fixed_pair_id: null,
      expected_absence_status: 'TEMPORARILY_AWAY',
      expected_temporary_away_skipped_turns: 2,
      expected_pending_check_in: false,
    },
  }])
  assert.match(text, /固定组合的两份登记已同时取消暂时离开/)
  assert.match(text, /已轮空次数会在游玩一轮后清零/)
})

test('refuses to defer or leave temporarily for a registration in the playing position', async () => {
  for (const fixedPair of [false, true]) {
    const player = {
      registration_id: 'registration-playing-player',
      profile_id: 'profile-playing-player',
      qq_number: '12345678',
      display_id: '小雨',
      machine_id: 'A',
      position: 'PLAYING',
      position_index: null,
      estimated_wait_minutes: 0,
      fixed_pair: fixedPair,
      deferred_once: false,
      temporarily_away: false,
      temporary_away_skipped_turns: 0,
      no_show_count: 0,
      last_no_show_action_was_defer: false,
      online_registration_pending_check_in: false,
    }
    const submitted = []
    const api = {
      getPlayers: async () => ({
        queue_id: 'queue-playing',
        queue_rules: {
          allow_defer_one_round: true,
          allow_temporary_leave: true,
        },
        players: [player],
      }),
      createQueueCommand: async (_qq, operation, fields) => {
        submitted.push({ operation, fields })
        return { command_id: 'command-playing', status: 'APPLIED' }
      },
    }
    const session = { platform: 'onebot', userId: '12345678', isDirect: true }

    await assert.rejects(
      changeAbsenceState(api, { commandWaitSeconds: 3 }, session, 'DEFER_ONE_ROUND'),
      { message: '处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。' },
    )
    await assert.rejects(
      changeAbsenceState(api, { commandWaitSeconds: 3 }, session, 'TEMPORARILY_LEAVE'),
      { message: '处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。' },
    )
    // Nothing is sent to the server: the answer is known beforehand.
    assert.deepEqual(submitted, [])
  }
})

test('names the rule that is switched off before the playing position', async () => {
  const api = {
    getPlayers: async () => ({
      queue_id: 'queue-playing',
      queue_rules: {
        allow_defer_one_round: false,
        allow_temporary_leave: false,
      },
      players: [{
        registration_id: 'registration-playing-player',
        profile_id: 'profile-playing-player',
        qq_number: '12345678',
        display_id: '小雨',
        machine_id: 'A',
        position: 'PLAYING',
        position_index: null,
        fixed_pair: false,
        deferred_once: false,
        temporarily_away: false,
        temporary_away_skipped_turns: 0,
        online_registration_pending_check_in: false,
      }],
    }),
    createQueueCommand: async () => assert.fail('no command may be sent'),
  }
  const session = { platform: 'onebot', userId: '12345678', isDirect: true }

  await assert.rejects(
    changeAbsenceState(api, { commandWaitSeconds: 3 }, session, 'DEFER_ONE_ROUND'),
    { message: '系统规则不允许暂缓一次。' },
  )
  await assert.rejects(
    changeAbsenceState(api, { commandWaitSeconds: 3 }, session, 'TEMPORARILY_LEAVE'),
    { message: '系统规则不允许暂时离开。' },
  )
})

function stoppedMachineScenario(playerFields = {}) {
  const sent = []
  const submitted = []
  const player = {
    registration_id: 'registration-stopped',
    profile_id: 'profile-stopped',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: false,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: null,
    fixed_pair: false,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
    preference: 'OPEN_TO_JOIN',
    ...playerFields,
  }
  const api = {
    getPlayers: async () => ({
      queue_id: 'queue-stopped',
      machine_configuration_revision: 7,
      queue_rules: {
        allow_defer_one_round: true,
        allow_temporary_leave: true,
      },
      players: [player],
    }),
    getQueue: async () => ({
      queue_id: 'queue-stopped',
      captured_at: 1,
      registration_open: true,
      terminal: { online: true },
      machines: {
        A: {
          id: 'A',
          name: '左侧·机台 A',
          operational: false,
          stop_reason: 'MAINTENANCE',
          playing: [],
          waiting_positions: [],
          registration_count: 1,
          configuration: { capacity: 2 },
        },
        B: {
          id: 'B',
          name: '右侧·机台 B',
          operational: true,
          playing: [],
          waiting_positions: [],
          registration_count: 0,
          configuration: { capacity: 2 },
        },
      },
    }),
    createQueueCommand: async (_qq, operation, fields) => {
      submitted.push({ operation, fields })
      return { command_id: 'command-stopped', status: 'APPLIED' }
    },
  }
  const session = (reply) => ({
    platform: 'onebot',
    userId: '12345678',
    isDirect: true,
    send: async message => sent.push(message),
    prompt: async () => reply,
  })
  return { api, sent, session, submitted }
}

test('lets a registration at a stopped machine leave the queue or move to another machine', async () => {
  const leaving = stoppedMachineScenario()
  const left = await leaveQueueFromBot(
    leaving.api,
    { commandWaitSeconds: 0 },
    leaving.session('确认退出排队'),
  )
  assert.deepEqual(leaving.submitted.map(command => command.operation), ['LEAVE_QUEUE'])
  assert.match(left, /退出排队/)

  const moving = stoppedMachineScenario()
  const moved = await transferQueueMachine(
    moving.api,
    { commandWaitSeconds: 0 },
    moving.session('确认切换机台'),
  )
  assert.deepEqual(moving.submitted.map(command => command.operation), ['TRANSFER_MACHINE'])
  assert.equal(moving.submitted[0].fields.target_machine_id, 'B')
  assert.match(moved, /登记已转至 右侧·机台 B/)
})

test('keeps every other operation waiting while the machine of a registration is stopped', async () => {
  for (const operation of [
    'DEFER_ONE_ROUND',
    'TEMPORARILY_LEAVE',
    'CANCEL_DEFER_ONE_ROUND',
    'CANCEL_TEMPORARY_LEAVE',
  ]) {
    const scenario = stoppedMachineScenario()
    await assert.rejects(
      changeAbsenceState(
        scenario.api,
        { commandWaitSeconds: 3 },
        scenario.session(''),
        operation,
      ),
      { message: '登记所在机台已停止使用，恢复正常使用前只能退出排队或转至其他机台。' },
    )
    assert.deepEqual(scenario.submitted, [])
  }
})

test('fixed-pair personal menu exposes the matching cancel action', () => {
  const actions = formatOwnQueueActions({
    position: 'WAITING',
    fixed_pair: true,
    deferred_once: false,
    temporarily_away: true,
    online_registration_pending_check_in: false,
  })

  assert.deepEqual(actions, [
    '取消暂时离开',
    '切换机台',
    '更改游玩偏好',
    '退出排队',
  ])
})

test('explains all fixed-pair absence operations as whole-group changes', () => {
  assert.match(
    absenceOperationSuccessMessage('DEFER_ONE_ROUND', true),
    /两份登记将同时暂缓一次.*跳过整组/,
  )
  assert.match(
    absenceOperationSuccessMessage('CANCEL_DEFER_ONE_ROUND', true),
    /两份登记已同时取消暂缓一次/,
  )
  assert.match(
    absenceOperationSuccessMessage('TEMPORARILY_LEAVE', true),
    /两份登记已同时设为暂时离开.*忽略整组.*整组登记会自动移除/s,
  )
  assert.match(
    absenceOperationSuccessMessage('CANCEL_TEMPORARY_LEAVE', true),
    /两份登记已同时取消暂时离开.*已轮空次数会在游玩一轮后清零/,
  )
  assert.doesNotMatch(
    absenceOperationSuccessMessage('CANCEL_TEMPORARY_LEAVE', false),
    /固定组合|两份登记|整组/,
  )
})

test('accepts concise machine letters, full names, and unique remarks', () => {
  const machines = [
    { id: 'A', name: '左侧 · 机台 A' },
    { id: 'B', name: '右侧 · 机台 B' },
    { id: 'C', name: '靠窗 · 机台 C' },
    { id: 'D', name: '入口 · 机台 D' },
    { id: 'J', name: '楼上 · 机台 J' },
  ]
  assert.equal(parseMachineChoice('A', machines), machines[0])
  assert.equal(parseMachineChoice('b', machines), machines[1])
  assert.equal(parseMachineChoice('c', machines), machines[2])
  assert.equal(parseMachineChoice('D', machines), machines[3])
  assert.equal(parseMachineChoice('j', machines), machines[4])
  assert.equal(parseMachineChoice('  a  ', machines), machines[0])
  assert.equal(parseMachineChoice('\t右侧\n', machines), machines[1])
  assert.equal(parseMachineChoice('机台 A', machines), machines[0])
  assert.equal(parseMachineChoice('机台c', machines), machines[2])
  assert.equal(parseMachineChoice('右侧·机台B', machines), machines[1])
  assert.equal(parseMachineChoice('入口·机台 D', machines), machines[3])
  assert.equal(parseMachineChoice('左侧', machines), machines[0])
  assert.equal(parseMachineChoice('右侧', machines), machines[1])
  assert.equal(parseMachineChoice('靠窗', machines), machines[2])
  assert.equal(parseMachineChoice('楼上', machines), machines[4])
  assert.equal(parseMachineChoice('左边', machines), null)
  assert.equal(parseMachineChoice('入口', [
    { id: 'A', name: '入口 · 机台 A' },
    { id: 'B', name: '入口 · 机台 B' },
  ]), null)
  assert.equal(parsePlayPreference('单人游玩'), 'SOLO')
  assert.equal(parsePlayPreference('允许他人加入'), 'OPEN_TO_JOIN')
  assert.equal(parsePlayPreference('每次询问'), null)
})

test('presents machine choices with the short letter first', () => {
  const machines = [
    { id: 'A', name: '左侧日框 · 机台 A', new_registration_estimated_wait_minutes: 12 },
    { id: 'B', name: '机台 B', new_registration_estimated_wait_minutes: 0 },
  ]
  assert.equal(formatMachineChoice(machines[0]), 'A（左侧日框，约 12 分钟后）')
  assert.equal(formatMachineChoice(machines[1]), 'B（预计很快可以游玩）')
  assert.equal(
    formatMachineChoice({
      id: 'D',
      name: '入口 · 机台 D',
      configuration: {
        game_type: 'CHUNITHM',
        server: 'CHINA',
        capacity: 1,
        solo_round_minutes: 12,
        shared_round_minutes: 15,
      },
      new_registration_estimated_wait_minutes: 6,
    }),
    'D（入口，仅单人游玩，约 6 分钟后）',
  )
  assert.equal(
    formatMachineChoice({ id: 'C', name: '机台 C' }),
    'C（暂时无法估算）',
  )
  assert.equal(
    formatMachineReplyHint(machines),
    '回复 A、B，也可以回复括号中的机台备注。',
  )
  assert.equal(
    formatMachineReplyHint([
      ...machines,
      { id: 'C', name: '靠窗 · 机台 C' },
      { id: 'D', name: '入口 · 机台 D' },
    ]),
    '回复 A、B、C、D，也可以回复括号中的机台备注。',
  )
})

test('formats every configured machine in A to J order and preserves groups', () => {
  const machine = id => ({
    id,
    group_id: id < 'F' ? 'group-1' : 'group-2',
    name: `${id} 区 · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: [],
  })
  const text = formatQueue({
    queue_id: 'queue-ten-machines',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machine_groups: [
      { id: 'group-1', name: '一楼' },
      { id: 'group-2', name: '二楼' },
    ],
    default_machine_group_id: 'group-1',
    machines: {
      ...Object.fromEntries([..."ABCDEFGHIJ"].reverse().map((id) => [id, machine(id)])),
    },
  })

  const offsets = [..."ABCDEFGHIJ"].map((id) => text.indexOf(`【${id} 区·机台 ${id}】`))
  assert.ok(offsets.every((offset) => offset >= 0))
  assert.deepEqual(offsets, [...offsets].sort((left, right) => left - right))
  assert.match(text, /分组：一楼[\s\S]*机台 E[\s\S]*分组：二楼[\s\S]*机台 F/)
})

test('groups long machine choices and splits a full ten-machine queue without truncation', () => {
  const groups = [
    { id: 'group-1', name: '一楼' },
    { id: 'group-2', name: '二楼' },
  ]
  const machines = Object.fromEntries([..."ABCDEFGHIJ"].map((id, machineIndex) => [id, {
    id,
    group_id: machineIndex < 5 ? groups[0].id : groups[1].id,
    name: `${id} 区 · 机台 ${id}`,
    operational: true,
    stop_reason: null,
    stop_reason_detail: null,
    playing_started_at: null,
    playing: [],
    waiting_positions: Array.from({ length: 20 }, (_, index) => ({
      index: index + 1,
      estimated_wait_minutes: index * 12,
      registrations: [{
        registration_id: `${id}-${index}`,
        display_id: `玩家${id}-${index}`,
        preference: 'SOLO',
        deferred_once: false,
        temporarily_away: false,
        temporary_away_skipped_turns: 0,
        fixed_pair: false,
        no_show_count: 0,
      }],
    })),
  }]))
  const queue = {
    queue_id: 'queue-full-ten-machines',
    captured_at: Date.now(),
    registration_open: true,
    terminal: { online: true },
    machine_groups: groups,
    default_machine_group_id: groups[0].id,
    machines,
  }

  assert.deepEqual(
    formatMachineChoiceLines(queue, [machines.A, machines.F]),
    ['一楼', ' - A（A 区，暂时无法估算）', '', '二楼', ' - F（F 区，暂时无法估算）'],
  )
  const messages = formatQueueMessages(queue, 1_000)
  assert.ok(messages.length > 1)
  assert.ok(messages.every((message) => message.length <= 1_000))
  const complete = messages.join('\n')
  for (const id of [..."ABCDEFGHIJ"]) {
    for (let index = 0; index < 20; index += 1) {
      assert.equal(complete.split(`玩家${id}-${index} (`).length - 1, 1)
    }
  }
})

test('does not offer stopped or full machines for a new registration', () => {
  const machine = {
    id: 'A',
    name: '左侧 · 机台 A',
    operational: true,
    playing: [],
    waiting_positions: [],
    registration_count: 19,
  }

  assert.equal(machineCanAcceptRegistration(machine), true)
  assert.equal(machineCanAcceptRegistration({ ...machine, registration_count: 20 }), false)
  assert.equal(machineCanAcceptRegistration({ ...machine, operational: false }), false)
})

test('parses only supported profile values', () => {
  assert.equal(parseGender('女'), 'FEMALE')
  assert.equal(parseGender('未知'), null)
  assert.equal(parsePreference('允许他人加入'), 'OPEN_TO_JOIN')
  assert.equal(parsePreference('固定组合'), null)
  assert.equal(parseNotificationPreference('开启'), true)
  assert.equal(parseNotificationPreference('关闭'), false)
  assert.equal(parseNotificationPreference('稍后'), null)
})

test('rejects unsafe or oversized profile nicknames', () => {
  assert.equal(nicknameValidationError('小雨'), null)
  assert.equal(nicknameValidationError('  '), '昵称应为 1 至 18 个字符。')
  assert.equal(nicknameValidationError('第一行\n第二行'), '昵称不能包含换行或控制字符。')
  assert.equal(nicknameValidationError('一二三四五六七八九十一二三四五六七八九'), '昵称应为 1 至 18 个字符。')
})

// 轮空一次 and the limits on postponing (maintainer, 2026-11-25).

function postponementScenario(playerFields = {}, laterFields = null) {
  const submitted = []
  const player = {
    registration_id: 'registration-postponed',
    profile_id: 'profile-postponed',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    machine_operational: true,
    position: 'WAITING',
    position_index: 1,
    estimated_wait_minutes: 6,
    fixed_pair: false,
    deferred_once: false,
    skips_one_turn: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    defer_one_round_used: false,
    online_registration_not_played: false,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
    online_registration_pending_check_in: false,
    ...playerFields,
  }
  const responses = [player, { ...player, ...(laterFields || {}) }].map(current => ({
    queue_id: 'queue-postponed',
    queue_rules: {
      allow_defer_one_round: true,
      allow_temporary_leave: true,
    },
    players: [current],
  }))
  const api = {
    getPlayers: async () => responses.length > 1 ? responses.shift() : responses[0],
    createQueueCommand: async (_qq, operation, fields) => {
      submitted.push({ operation, fields })
      return { command_id: 'command-postponed', status: 'APPLIED', result_detail: null }
    },
  }
  const session = { platform: 'onebot', userId: '12345678', isDirect: true }
  return { api, session, submitted }
}

test('sets 轮空一次 for a waiting registration and explains the count', async () => {
  const scenario = postponementScenario({}, { skips_one_turn: true })

  const text = await changeAbsenceState(
    scenario.api,
    { commandWaitSeconds: 3 },
    scenario.session,
    'SKIP_ONE_TURN',
  )

  assert.equal(scenario.submitted.length, 1)
  assert.equal(scenario.submitted[0].operation, 'SKIP_ONE_TURN')
  assert.equal(scenario.submitted[0].fields.expected_absence_status, 'NONE')
  assert.equal(
    text,
    '登记将轮空一次。下一次轮到时会移至队尾，计一次轮空，随后自动解除。',
  )
})

test('cancels 轮空一次 against the confirmed 轮空一次 state', async () => {
  const scenario = postponementScenario(
    { skips_one_turn: true, temporary_away_skipped_turns: 1 },
    { skips_one_turn: false },
  )

  const text = await changeAbsenceState(
    scenario.api,
    { commandWaitSeconds: 3 },
    scenario.session,
    'CANCEL_SKIP_ONE_TURN',
  )

  assert.equal(scenario.submitted[0].operation, 'CANCEL_SKIP_ONE_TURN')
  assert.equal(scenario.submitted[0].fields.expected_absence_status, 'SKIP_ONE_TURN')
  assert.equal(scenario.submitted[0].fields.expected_temporary_away_skipped_turns, 1)
  assert.equal(text, '登记已取消轮空一次，将按照当前等待顺序正常轮候。')
})

test('refuses the postponements a player cannot ask for without asking the server', async () => {
  const cases = [
    [
      { defer_one_round_used: true },
      'DEFER_ONE_ROUND',
      '这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。',
    ],
    ...['DEFER_ONE_ROUND', 'SKIP_ONE_TURN', 'TEMPORARILY_LEAVE'].map(operation => [
      { online_registration_not_played: true },
      operation,
      '已签到的线上登记在游玩一轮之前不能延后。',
    ]),
    [
      { position: 'PLAYING', position_index: null },
      'SKIP_ONE_TURN',
      '处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。',
    ],
  ]
  for (const [fields, operation, message] of cases) {
    const scenario = postponementScenario(fields)
    await assert.rejects(
      changeAbsenceState(scenario.api, { commandWaitSeconds: 3 }, scenario.session, operation),
      { message },
    )
    assert.deepEqual(scenario.submitted, [])
  }

  // A used 暂缓一次 leaves the other two.
  const skipping = postponementScenario({ defer_one_round_used: true }, { skips_one_turn: true })
  await changeAbsenceState(
    skipping.api,
    { commandWaitSeconds: 3 },
    skipping.session,
    'SKIP_ONE_TURN',
  )
  assert.equal(skipping.submitted[0].operation, 'SKIP_ONE_TURN')
})

test('the personal menu follows 轮空一次 and the postponement limits', () => {
  const player = {
    position: 'WAITING',
    deferred_once: false,
    temporarily_away: false,
    online_registration_pending_check_in: false,
  }
  const rules = { allow_defer_one_round: true, allow_temporary_leave: true }

  assert.deepEqual(formatOwnQueueActions({ ...player, skips_one_turn: true }, rules), [
    '取消轮空一次',
    '切换机台',
    '更改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(formatOwnQueueActions({ ...player, defer_one_round_used: true }, rules), [
    '轮空一次',
    '暂时离开',
    '切换机台',
    '更改游玩偏好',
    '退出排队',
  ])
  assert.deepEqual(
    formatOwnQueueActions({ ...player, online_registration_not_played: true }, rules),
    ['切换机台', '更改游玩偏好', '退出排队'],
  )
})

test('writes a set state with 将 before the turn', () => {
  const base = {
    registration_id: 'registration-1',
    profile_id: 'profile-1',
    qq_number: '12345678',
    display_id: '小雨',
    machine_id: 'A',
    position: 'WAITING',
    position_index: 2,
    estimated_wait_minutes: 8,
    deferred_once: false,
    temporarily_away: false,
    temporary_away_skipped_turns: 0,
    no_show_count: 0,
    last_no_show_action_was_defer: false,
  }

  assert.match(formatOwnQueue([{ ...base, deferred_once: true }]), /将暂缓一次/)
  const skipping = formatOwnQueue([{
    ...base,
    skips_one_turn: true,
    temporary_away_skipped_turns: 1,
  }])
  assert.match(skipping, /将轮空一次·已轮空 1 次/)
  assert.match(skipping, /取消轮空一次/)

  assert.equal(
    absenceOperationSuccessMessage('DEFER_ONE_ROUND', false),
    '登记将暂缓一次。下一次轮到时会跳过这份登记，之后自动解除暂缓；队列会显示预计下次游玩的位置，真实等待顺序不变。',
  )
  assert.match(
    absenceOperationSuccessMessage('SKIP_ONE_TURN', true),
    /^固定组合的两份登记将同时轮空一次。下一次轮到时整组会移至队尾，计一次轮空，随后自动解除。$/,
  )
  assert.equal(
    absenceOperationSuccessMessage('CANCEL_SKIP_ONE_TURN', true),
    '固定组合的两份登记已同时取消轮空一次，将按照当前等待顺序正常轮候。',
  )
})

test('the queue list marks a registration set to 轮空一次', () => {
  const text = formatQueue({
    queue_id: 'queue-1',
    captured_at: 1,
    registration_open: true,
    terminal: { online: true },
    machines: {
      A: {
        id: 'A',
        name: '左侧·机台 A',
        operational: true,
        stop_reason: null,
        stop_reason_detail: null,
        playing_started_at: null,
        playing: [],
        waiting_positions: [{
          index: 1,
          estimated_wait_minutes: 4,
          registrations: [
            {
              registration_id: 'r1',
              display_id: '小雨',
              preference: 'SOLO',
              deferred_once: false,
              skips_one_turn: true,
              temporarily_away: false,
              temporary_away_skipped_turns: 2,
              fixed_pair: false,
              no_show_count: 0,
              estimated_wait_minutes: 4,
            },
            {
              registration_id: 'r2',
              display_id: '小风',
              preference: 'SOLO',
              deferred_once: true,
              temporarily_away: false,
              temporary_away_skipped_turns: 0,
              fixed_pair: false,
              no_show_count: 0,
              estimated_wait_minutes: 4,
            },
          ],
        }],
        registration_count: 2,
        configuration: { capacity: 2 },
      },
    },
  })

  assert.match(text, / {4}- 将轮空一次·已轮空 2 次/)
  assert.match(text, / {4}- 将暂缓一次/)
})
