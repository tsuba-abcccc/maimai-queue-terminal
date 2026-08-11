const test = require('node:test')
const assert = require('node:assert/strict')

const {
  NOTIFICATION_MAX_ATTEMPTS,
  NOTIFICATION_RETRY_DELAYS_MS,
  QueueApi,
  formatNotificationPreferenceMenu,
  isNotificationDeliveryDue,
  isNotificationDeliveryTerminal,
  nextNotificationFailure,
  notificationFieldForEvent,
  pollNotifications,
  profileAllowsEventNotification,
  redactQqNumber,
} = require('../lib')

class MemoryDatabase {
  constructor() {
    this.states = new Map()
    this.deliveries = new Map()
  }

  async get(table, query) {
    if (table === 'maimai_q_state') {
      return [...this.states.values()].filter(row => matches(row, query))
    }
    return [...this.deliveries.values()].filter(row => matches(row, query))
  }

  async upsert(table, rows) {
    for (const row of rows) {
      if (table === 'maimai_q_state') {
        this.states.set(row.key, { ...row })
      } else {
        this.deliveries.set(deliveryKey(row), { ...row })
      }
    }
  }

  async remove(table, query) {
    const source = table === 'maimai_q_state' ? this.states : this.deliveries
    for (const [key, row] of source) {
      if (matches(row, query)) source.delete(key)
    }
  }
}

function matches(row, query) {
  return Object.entries(query).every(([key, value]) => row[key] === value)
}

function deliveryKey(row) {
  return `${row.queueId}:${row.eventId}:${row.qqNumber}`
}

function event(cursor, eventId, qq) {
  return {
    cursor,
    event_id: eventId,
    occurred_at: 1_000 + cursor,
    machine_id: 'A',
    type: 'PLAYING_CHANGED',
    title: '排队状态已更新',
    detail: '请查看当前排队位置。',
    affected_players: [{
      registration_id: `registration-${cursor}`,
      profile_id: `profile-${cursor}`,
      qq_number: qq,
    }],
  }
}

function eventPage(after) {
  const events = [
    event(1, 'event-1', '11111'),
    event(2, 'event-2', '22222'),
  ].filter(item => item.cursor > after)
  return {
    queue_id: 'queue-1',
    events,
    next_cursor: events.at(-1)?.cursor ?? after,
    latest_cursor: 2,
    has_more: false,
  }
}

function config() {
  return {
    apiBase: 'https://queue.example.test',
    botToken: 'test-token',
    notificationEnabled: true,
    notificationIntervalSeconds: 5,
    commandWaitSeconds: 15,
  }
}

function notificationProfile(qq, overrides = {}) {
  return {
    profile_id: `profile-${qq}`,
    nickname: `玩家${qq}`,
    gender: 'UNDISCLOSED',
    default_preference: 'OPEN_TO_JOIN',
    qq_number: qq,
    usage_count: 1,
    qq_visibility: 'TERMINAL_ONLY',
    notification_enabled: true,
    notify_queue_changes: true,
    notify_playing_position: true,
    notify_online_check_in: true,
    notify_absence: true,
    notify_machine_status: true,
    setup_version: 1,
    profile_revision: 1,
    updated_at: 1_000,
    ...overrides,
  }
}

function profileResponse(qq, overrides = {}) {
  return {
    bot_qq: '10000',
    profiles: [notificationProfile(qq, overrides)],
  }
}

function logger() {
  return { debug() {}, info() {}, warn() {} }
}

test('private QQ lookups use JSON bodies instead of URL parameters', async () => {
  const calls = []
  const ctx = {
    http: {
      async post(url, body, options) {
        calls.push({ url, body, options })
        return url.endsWith('/players')
          ? { queue_id: 'queue-1', players: [] }
          : { profiles: [] }
      },
    },
  }
  const api = new QueueApi(ctx, config())

  await api.getPlayers('12345678')
  await api.getProfiles('12345678')

  assert.deepEqual(calls.map(call => call.url), [
    'https://queue.example.test/api/queue-bot/players',
    'https://queue.example.test/api/queue-bot/profiles',
  ])
  assert.ok(calls.every(call => !call.url.includes('12345678')))
  assert.ok(calls.every(call => call.body.qq === '12345678'))
  assert.ok(calls.every(call => call.options.headers.Authorization === 'Bearer test-token'))
})

test('notification failures follow bounded backoff and become terminal', () => {
  const initial = {
    queueId: 'queue-1',
    eventId: 'event-1',
    qqNumber: '12345678',
    status: 'PENDING',
    attempts: 0,
    nextRetryAt: 0,
    lastError: '',
    updatedAt: 0,
  }

  let state = initial
  let now = 10_000
  for (let attempt = 1; attempt <= NOTIFICATION_MAX_ATTEMPTS; attempt += 1) {
    state = nextNotificationFailure(state, now, '发送失败')
    assert.equal(state.attempts, attempt)
    if (attempt < NOTIFICATION_MAX_ATTEMPTS) {
      assert.equal(state.status, 'PENDING')
      assert.equal(state.nextRetryAt, now + NOTIFICATION_RETRY_DELAYS_MS[attempt - 1])
      assert.equal(isNotificationDeliveryDue(state, state.nextRetryAt - 1), false)
      assert.equal(isNotificationDeliveryDue(state, state.nextRetryAt), true)
      now = state.nextRetryAt
    }
  }

  assert.equal(state.status, 'FAILED')
  assert.equal(state.nextRetryAt, 0)
  assert.equal(isNotificationDeliveryTerminal(state), true)
  assert.equal(isNotificationDeliveryDue(state, Number.MAX_SAFE_INTEGER), false)
})

test('redacts every occurrence of the current QQ from adapter errors', () => {
  const redacted = redactQqNumber(
    '向 12345678 发送失败：OneBot 用户 12345678 不可用',
    '12345678',
  )

  assert.equal(redacted, '向 12****78 发送失败：OneBot 用户 12****78 不可用')
  assert.doesNotMatch(redacted, /12345678/)
})

test('reports the active Bot QQ and deployed client versions to the private identity endpoint', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    return { status: 200, data: { ok: true } }
  }
  const api = new QueueApi({ http }, config())

  await api.updateIdentity('123456789', 'v0.10.1')

  assert.equal(requests.length, 1)
  assert.equal(requests[0][0], 'POST')
  assert.equal(
    requests[0][1],
    'https://queue.example.test/api/queue-bot/identity',
  )
  assert.deepEqual(requests[0][2].data, {
    bot_qq: '123456789',
    bot_version: '0.3.12',
    website_version: '0.10.1',
  })
  assert.equal(
    requests[0][2].headers.Authorization,
    'Bearer test-token',
  )
})

test('falls back to the legacy Bot identity payload on an older server', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    return requests.length === 1
      ? { status: 400, data: { error: '请求内容必须只包含 bot_qq' } }
      : { status: 200, data: { ok: true } }
  }
  const api = new QueueApi({ http }, config())

  await api.updateIdentity('123456789', '0.10.1')

  assert.equal(requests.length, 2)
  assert.deepEqual(requests[0][2].data, {
    bot_qq: '123456789',
    bot_version: '0.3.12',
    website_version: '0.10.1',
  })
  assert.deepEqual(requests[1][2].data, { bot_qq: '123456789' })
})

test('reads and validates the deployed website version manifest', async () => {
  const ctx = {
    http: {
      async get(url) {
        assert.equal(url, 'https://site.example.test/queue-client-version.json')
        return { version: 'v0.10.1' }
      },
    },
  }
  const api = new QueueApi(ctx, {
    ...config(),
    publicQueueUrl: 'https://site.example.test',
  })

  assert.equal(await api.getWebsiteVersion(), '0.10.1')
})

test('submits every notification preference through player profile updates', async () => {
  const requests = []
  const http = async (...args) => {
    requests.push(args)
    return {
      status: 202,
      data: {
        command_id: '00000000-0000-0000-0000-000000000401',
        status: 'PENDING',
        result_detail: null,
      },
    }
  }
  const api = new QueueApi({ http }, config())
  const updates = [
    ['notification_enabled', false],
    ['notify_queue_changes', false],
    ['notify_playing_position', true],
    ['notify_online_check_in', false],
    ['notify_absence', false],
    ['notify_machine_status', true],
  ]

  for (const [field, enabled] of updates) {
    await api.updateProfile(
      '00000000-0000-0000-0000-000000000001',
      '12345678',
      { [field]: enabled },
    )
  }

  assert.equal(requests.length, updates.length)
  for (const [index, [field, enabled]] of updates.entries()) {
    const [, url, options] = requests[index]
    assert.equal(
      url,
      'https://queue.example.test/api/queue-bot/profiles/' +
        '00000000-0000-0000-0000-000000000001',
    )
    assert.equal(options.data.actor_qq, '12345678')
    assert.equal(options.data[field], enabled)
    assert.equal(typeof options.data.request_id, 'string')
  }
})

test('maps queue events to the same notification categories as the server', () => {
  const expectations = {
    PLAYING_CHANGED: 'notify_playing_position',
    ONLINE_REGISTRATION_ADDED: 'notify_online_check_in',
    ONLINE_CHECK_IN_COMPLETED: 'notify_online_check_in',
    ONLINE_CHECK_IN_TIMED_OUT: 'notify_online_check_in',
    ONLINE_CHECK_IN_MISSED: 'notify_online_check_in',
    NO_SHOW_DEFERRED: 'notify_absence',
    NO_SHOW_MOVED_TO_TAIL: 'notify_absence',
    NO_SHOW_REMOVED: 'notify_absence',
    TEMPORARY_AWAY_EXPIRED: 'notify_absence',
    ABSENCE_CHANGED: 'notify_absence',
    MACHINE_STOPPED: 'notify_machine_status',
    MACHINE_RESTORED: 'notify_machine_status',
    REGISTRATION_OPENED: 'notify_machine_status',
    REGISTRATION_CLOSED: 'notify_machine_status',
    REGISTRATION_UPDATED: 'notify_queue_changes',
  }

  for (const [eventType, field] of Object.entries(expectations)) {
    assert.equal(notificationFieldForEvent(eventType), field)
  }
})

test('uses documented defaults and formats the cloud notification menu', () => {
  const profile = notificationProfile('12345678', {
    notification_enabled: undefined,
    notify_queue_changes: undefined,
    notify_playing_position: undefined,
    notify_online_check_in: undefined,
    notify_absence: undefined,
    notify_machine_status: undefined,
  })

  assert.equal(profileAllowsEventNotification(profile, 'REGISTRATION_UPDATED'), true)
  assert.equal(profileAllowsEventNotification(profile, 'PLAYING_CHANGED'), false)
  assert.equal(profileAllowsEventNotification(profile, 'ONLINE_CHECK_IN_COMPLETED'), true)
  assert.equal(profileAllowsEventNotification(profile, 'ABSENCE_CHANGED'), true)
  assert.equal(profileAllowsEventNotification(profile, 'MACHINE_STOPPED'), false)
  const playingOnly = notificationProfile('12345678', {
    notify_queue_changes: false,
    notify_playing_position: true,
    notify_online_check_in: false,
    notify_absence: false,
    notify_machine_status: false,
  })
  assert.equal(profileAllowsEventNotification(
    playingOnly,
    'ONLINE_CHECK_IN_MISSED',
    ['ONLINE_CHECK_IN', 'PLAYING_POSITION', 'QUEUE_CHANGES'],
  ), true)

  const menu = formatNotificationPreferenceMenu(profile, true, '123456789')
  assert.match(menu, /总开关：已开启/)
  assert.match(menu, /游玩位置变化：已关闭/)
  assert.match(menu, /QQ Bot（123456789）/)
  assert.match(menu, /关闭排队通知/)
  assert.match(menu, /开启游玩位置通知/)

  const unavailableMenu = formatNotificationPreferenceMenu(profile, false, '123456789')
  assert.match(unavailableMenu, /系统通知：暂未启用/)
})

test('disabled recipients are skipped without blocking later queue events', async () => {
  const database = new MemoryDatabase()
  const currentConfig = config()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) { sends.push(qq) },
  }
  const api = {
    async getEvents(after) { return eventPage(after) },
    async getProfiles(qq) {
      return profileResponse(qq, {
        notification_enabled: qq !== '11111',
      })
    },
    async getPlayers() { return { queue_id: 'queue-1', players: [] } },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    currentConfig,
    logger(),
  )

  assert.deepEqual(sends, ['22222'])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.equal(cursor.cursor, 2)
})

test('a disabled event category is skipped without sending a private message', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) { sends.push(qq) },
  }
  const api = {
    async getEvents(after) {
      const page = eventPage(after)
      return { ...page, events: page.events.slice(0, 1), latest_cursor: 1 }
    },
    async getProfiles(qq) {
      return profileResponse(qq, { notify_playing_position: false })
    },
    async getPlayers() {
      assert.fail('不应查询已关闭分项对应的排队状态')
    },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    config(),
    logger(),
  )

  assert.deepEqual(sends, [])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.equal(cursor.cursor, 1)
})

test('a new queue scope starts at its latest cursor instead of replaying history', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 2 }),
  }])
  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) { sends.push(qq) },
  }
  const eventRequests = []
  const api = {
    async getEvents(after) {
      eventRequests.push(after)
      const events = [event(3, 'queue-reset', '11111')]
        .filter(item => item.cursor > after)
      return {
        queue_id: 'queue-2',
        events,
        next_cursor: events.at(-1)?.cursor ?? after,
        latest_cursor: 3,
        has_more: false,
      }
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() { return { queue_id: 'queue-2', players: [] } },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    config(),
    logger(),
  )

  assert.deepEqual(sends, [])
  assert.deepEqual(eventRequests, [2, 0])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.deepEqual(cursor, { queueId: 'queue-2', cursor: 3 })
})

test('a new queue scope still retries an unfinished delivery without replaying other history', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [
    {
      key: 'event-cursor:https://queue.example.test',
      value: JSON.stringify({ queueId: 'queue-old', cursor: 7 }),
    },
    {
      key: 'event-cursor:https://queue.example.test:scope:queue-new',
      value: JSON.stringify({ queueId: 'queue-other', cursor: 1 }),
    },
  ])
  await database.upsert('maimai_q_delivery', [{
    queueId: 'queue-new',
    eventId: 'pending-event',
    qqNumber: '11111',
    status: 'PENDING',
    attempts: 0,
    nextRetryAt: 0,
    lastError: '',
    updatedAt: 0,
  }])

  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) { sends.push(qq) },
  }
  const api = {
    async getEvents(after) {
      const events = [
        event(1, 'old-history', '22222'),
        event(2, 'pending-event', '11111'),
        event(3, 'new-history', '33333'),
      ].filter(item => item.cursor > after)
      return {
        queue_id: 'queue-new',
        events,
        next_cursor: events.at(-1)?.cursor ?? after,
        latest_cursor: 3,
        has_more: false,
      }
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() { return { queue_id: 'queue-new', players: [] } },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    config(),
    logger(),
  )

  assert.deepEqual(sends, ['11111'])
  assert.equal(database.deliveries.has('queue-new:pending-event:11111'), false)
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.deepEqual(cursor, { queueId: 'queue-new', cursor: 3 })
})

test('test notifications use an isolated scope even with the same public queue id', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 2 }),
  }])
  const messages = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq, message) { messages.push({ qq, message }) },
  }
  const eventRequests = []
  const api = {
    async getEvents(after) {
      eventRequests.push(after)
      const events = [event(3, 'test-event-1', '11111')]
        .filter(item => item.cursor > after)
      return {
        queue_id: 'queue-1',
        notification_scope_id: 'test:terminal-2:queue-1',
        test_data: true,
        events,
        next_cursor: events.at(-1)?.cursor ?? after,
        latest_cursor: 3,
        has_more: false,
      }
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() { return { queue_id: 'queue-1', players: [] } },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    config(),
    logger(),
  )

  assert.equal(messages.length, 0)
  assert.deepEqual(eventRequests, [2, 0])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.deepEqual(cursor, {
    queueId: 'test:terminal-2:queue-1',
    cursor: 3,
  })
})

test('test notification polling preserves unfinished official deliveries', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  let activeScope = 'official'
  let officialDeliveryAvailable = false
  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq, message) {
      sends.push({ qq, message })
      if (qq === '11111' && !officialDeliveryAvailable) {
        throw new Error('发送失败')
      }
    },
  }
  const api = {
    async getEvents(after) {
      const testData = activeScope === 'test'
      const scopeId = testData ? 'test:terminal-2:queue-1' : 'queue-1'
      const available = testData
        ? [event(2, 'test-event-1', '22222')]
        : [event(1, 'official-event-1', '11111')]
      const events = available.filter(item => item.cursor > after)
      return {
        queue_id: 'queue-1',
        notification_scope_id: scopeId,
        test_data: testData,
        events,
        next_cursor: events.at(-1)?.cursor ?? after,
        latest_cursor: available.at(-1).cursor,
        has_more: false,
      }
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() { return { queue_id: 'queue-1', players: [] } },
  }
  const ctx = { database, bots: [bot] }

  await pollNotifications(ctx, api, config(), logger())
  assert.equal(
    database.deliveries.get('queue-1:official-event-1:11111').status,
    'PENDING',
  )

  activeScope = 'test'
  await pollNotifications(ctx, api, config(), logger())
  assert.equal(
    database.deliveries.get('queue-1:official-event-1:11111').status,
    'PENDING',
  )

  const pendingOfficial = database.deliveries.get(
    'queue-1:official-event-1:11111',
  )
  database.deliveries.set('queue-1:official-event-1:11111', {
    ...pendingOfficial,
    nextRetryAt: 0,
  })
  officialDeliveryAvailable = true
  activeScope = 'official'
  await pollNotifications(ctx, api, config(), logger())

  assert.equal(
    database.deliveries.has('queue-1:official-event-1:11111'),
    false,
  )
  assert.deepEqual(sends.map(item => item.qq), ['11111', '11111'])
  const officialCursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test:scope:queue-1',
  ).value)
  const testCursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test:scope:test:terminal-2:queue-1',
  ).value)
  assert.equal(officialCursor.cursor, 1)
  assert.equal(testCursor.cursor, 2)
})

test('disabling notifications cancels an existing pending retry', async () => {
  const database = new MemoryDatabase()
  const currentConfig = config()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  const sends = []
  let firstRecipientEnabled = true
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) {
      sends.push(qq)
      if (qq === '11111') throw new Error('发送失败')
    },
  }
  const api = {
    async getEvents(after) { return eventPage(after) },
    async getProfiles(qq) {
      return profileResponse(qq, {
        notification_enabled: qq !== '11111' || firstRecipientEnabled,
      })
    },
    async getPlayers() { return { queue_id: 'queue-1', players: [] } },
  }
  const ctx = { database, bots: [bot] }

  await pollNotifications(ctx, api, currentConfig, logger())
  assert.deepEqual(sends, ['11111', '22222'])
  assert.equal(
    database.deliveries.get('queue-1:event-1:11111').status,
    'PENDING',
  )

  firstRecipientEnabled = false
  await pollNotifications(ctx, api, currentConfig, logger())

  assert.deepEqual(sends, ['11111', '22222'])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.equal(cursor.cursor, 2)
  assert.equal(database.deliveries.size, 0)
})

test('uses an active OneBot when an earlier matching instance is offline', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  const sends = []
  const offlineBot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: false,
    async sendPrivateMessage() {
      assert.fail('不应使用离线 OneBot 发送通知')
    },
  }
  const onlineBot = {
    platform: 'onebot',
    selfId: '20000',
    isActive: true,
    async sendPrivateMessage(qq) {
      sends.push(qq)
    },
  }
  const ctx = { database, bots: [offlineBot, onlineBot] }
  const api = {
    async getEvents(after) {
      return eventPage(after)
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() {
      return { queue_id: 'queue-1', players: [] }
    },
  }

  await pollNotifications(ctx, api, config(), logger())

  assert.deepEqual(sends, ['11111', '22222'])
})

test('a failing recipient does not block later delivery or cause duplicates', async () => {
  const database = new MemoryDatabase()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])

  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq, message) {
      sends.push({ qq, message })
      if (qq === '11111') throw new Error('向 QQ 11111 发送失败')
    },
  }
  const ctx = { database, bots: [bot] }
  const logEntries = []
  const testLogger = {
    debug(...values) { logEntries.push(values) },
    info(...values) { logEntries.push(values) },
    warn(...values) { logEntries.push(values) },
  }
  const api = {
    async getEvents(after) {
      return eventPage(after)
    },
    async getProfiles(qq) { return profileResponse(qq) },
    async getPlayers() {
      return { queue_id: 'queue-1', players: [] }
    },
  }

  await pollNotifications(ctx, api, config(), testLogger)

  const firstCursor = JSON.parse(database.states.values().next().value.value)
  assert.equal(firstCursor.cursor, 0)
  assert.deepEqual(sends.map(item => item.qq), ['11111', '22222'])
  const pending = database.deliveries.get('queue-1:event-1:11111')
  assert.equal(pending.status, 'PENDING')
  assert.equal(pending.lastError, '向 QQ 11*11 发送失败')
  assert.doesNotMatch(pending.lastError, /11111/)
  assert.ok(logEntries.flat().every(value => !String(value).includes('11111')))
  assert.equal(database.deliveries.get('queue-1:event-2:22222').status, 'DELIVERED')

  await pollNotifications(ctx, api, config(), testLogger)
  assert.deepEqual(sends.map(item => item.qq), ['11111', '22222'])

  const failed = database.deliveries.get('queue-1:event-1:11111')
  database.deliveries.set('queue-1:event-1:11111', {
    ...failed,
    attempts: NOTIFICATION_MAX_ATTEMPTS - 1,
    nextRetryAt: 0,
  })
  await pollNotifications(ctx, api, config(), testLogger)

  const finalCursor = JSON.parse(database.states.values().next().value.value)
  assert.equal(finalCursor.cursor, 2)
  assert.deepEqual(sends.map(item => item.qq), ['11111', '22222', '11111'])
  assert.equal(database.deliveries.get('queue-1:event-1:11111').status, 'FAILED')
  assert.equal(database.deliveries.has('queue-1:event-2:22222'), false)
  assert.ok(logEntries.flat().every(value => !String(value).includes('11111')))
})
