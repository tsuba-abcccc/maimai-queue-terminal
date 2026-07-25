const test = require('node:test')
const assert = require('node:assert/strict')

const {
  NOTIFICATION_MAX_ATTEMPTS,
  NOTIFICATION_RETRY_DELAYS_MS,
  QueueApi,
  isNotificationDeliveryDue,
  isNotificationDeliveryTerminal,
  nextNotificationFailure,
  pollNotifications,
  readNotificationPreference,
  redactQqNumber,
  writeNotificationPreference,
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

test('queue notifications default to enabled and persist per QQ', async () => {
  const database = new MemoryDatabase()
  const ctx = { database }
  const currentConfig = config()

  assert.equal(
    await readNotificationPreference(ctx, currentConfig, '12345678'),
    true,
  )
  await writeNotificationPreference(ctx, currentConfig, '12345678', false)
  assert.equal(
    await readNotificationPreference(ctx, currentConfig, '12345678'),
    false,
  )
  assert.equal(
    await readNotificationPreference(ctx, currentConfig, '87654321'),
    true,
  )
})

test('disabled recipients are skipped without blocking later queue events', async () => {
  const database = new MemoryDatabase()
  const currentConfig = config()
  await database.upsert('maimai_q_state', [{
    key: 'event-cursor:https://queue.example.test',
    value: JSON.stringify({ queueId: 'queue-1', cursor: 0 }),
  }])
  await writeNotificationPreference(
    { database },
    currentConfig,
    '11111',
    false,
  )
  const sends = []
  const bot = {
    platform: 'onebot',
    selfId: '10000',
    isActive: true,
    async sendPrivateMessage(qq) { sends.push(qq) },
  }
  const api = {
    async getEvents(after) { return eventPage(after) },
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

test('existing notification cursors continue through a new queue batch', async () => {
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
  const api = {
    async getEvents(after) {
      assert.equal(after, 2)
      return {
        queue_id: 'queue-2',
        events: [event(3, 'queue-reset', '11111')],
        next_cursor: 3,
        latest_cursor: 3,
        has_more: false,
      }
    },
    async getPlayers() { return { queue_id: 'queue-2', players: [] } },
  }

  await pollNotifications(
    { database, bots: [bot] },
    api,
    config(),
    logger(),
  )

  assert.deepEqual(sends, ['11111'])
  const cursor = JSON.parse(database.states.get(
    'event-cursor:https://queue.example.test',
  ).value)
  assert.deepEqual(cursor, { queueId: 'queue-2', cursor: 3 })
})

test('disabling notifications cancels an existing pending retry', async () => {
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
    async sendPrivateMessage(qq) {
      sends.push(qq)
      if (qq === '11111') throw new Error('发送失败')
    },
  }
  const api = {
    async getEvents(after) { return eventPage(after) },
    async getPlayers() { return { queue_id: 'queue-1', players: [] } },
  }
  const ctx = { database, bots: [bot] }

  await pollNotifications(ctx, api, currentConfig, logger())
  assert.deepEqual(sends, ['11111', '22222'])
  assert.equal(
    database.deliveries.get('queue-1:event-1:11111').status,
    'PENDING',
  )

  await writeNotificationPreference(ctx, currentConfig, '11111', false)
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
