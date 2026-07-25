import { Bot, Context, Schema, Session } from "koishi";
import { randomUUID } from "node:crypto";

export const name = "maimai-q";
export const inject = { required: ["database", "http"] };

interface PluginState {
  key: string;
  value: string;
}

type NotificationDeliveryStatus = "PENDING" | "DELIVERED" | "FAILED";

export interface NotificationDelivery {
  queueId: string;
  eventId: string;
  qqNumber: string;
  status: NotificationDeliveryStatus;
  attempts: number;
  nextRetryAt: number;
  lastError: string;
  updatedAt: number;
}

declare module "koishi" {
  interface Tables {
    maimai_q_state: PluginState;
    maimai_q_delivery: NotificationDelivery;
  }
}

type PlayerGender = "MALE" | "FEMALE" | "UNDISCLOSED";
type ProfilePreference = "SOLO" | "OPEN_TO_JOIN" | "ASK_EVERY_TIME";

interface QueueRegistration {
  registration_id: string;
  display_id: string;
  preference: "SOLO" | "OPEN_TO_JOIN";
  deferred_once: boolean;
  temporarily_away: boolean;
  temporary_away_skipped_turns: number;
  no_show_count: number;
}

interface WaitingPosition {
  index: number;
  estimated_wait_minutes: number | null;
  registrations: QueueRegistration[];
}

interface QueueMachine {
  id: "A" | "B";
  name: string;
  operational: boolean;
  stop_reason: string | null;
  playing_started_at: number | null;
  playing: QueueRegistration[];
  waiting_positions: WaitingPosition[];
}

interface QueueStatus {
  queue_id: string;
  captured_at: number;
  registration_open: boolean;
  onebot_sync_enabled?: boolean;
  business_hours?: {
    enabled: boolean;
    outside: boolean;
    closing_soon: boolean;
    closing_grace?: boolean;
    closes_at: number | null;
    registration_closes_at?: number | null;
  };
  terminal: { online: boolean };
  machines: { A: QueueMachine; B: QueueMachine };
}

interface BotPlayer {
  registration_id: string;
  profile_id: string;
  qq_number: string;
  display_id: string;
  machine_id: "A" | "B";
  position: "PLAYING" | "WAITING";
  position_index: number | null;
  estimated_wait_minutes: number | null;
  deferred_once: boolean;
  temporarily_away: boolean;
  temporary_away_skipped_turns: number;
  no_show_count: number;
  last_no_show_action_was_defer: boolean;
}

interface BotPlayersResponse {
  queue_id: string;
  players: BotPlayer[];
}

interface PlayerProfile {
  profile_id: string;
  nickname: string;
  gender: PlayerGender;
  default_preference: ProfilePreference;
  qq_number: string | null;
  usage_count: number;
  updated_at: number;
}

interface ProfilesResponse {
  profiles: PlayerProfile[];
}

interface AffectedPlayer {
  registration_id: string;
  profile_id: string;
  qq_number: string;
}

interface QueueEvent {
  cursor: number;
  event_id: string;
  occurred_at: number;
  machine_id: "A" | "B" | null;
  type: string;
  title: string;
  detail: string;
  operation_source?: string;
  affected_players: AffectedPlayer[];
}

interface EventsResponse {
  queue_id: string;
  events: QueueEvent[];
  next_cursor: number;
  latest_cursor: number;
  has_more: boolean;
}

interface RemoteCommand {
  command_id: string;
  status: "PENDING" | "APPLIED" | "REJECTED";
  result_detail: string | null;
}

interface EventCursor {
  queueId: string;
  cursor: number;
}

export const NOTIFICATION_RETRY_DELAYS_MS = [
  5_000,
  15_000,
  30_000,
  60_000,
  120_000,
] as const;
export const NOTIFICATION_MAX_ATTEMPTS = NOTIFICATION_RETRY_DELAYS_MS.length +
  1;
const PROFILE_INPUT_TIMEOUT_MS = 60_000;

export const HELP_TEXT = [
  "你好！",
  "可用的命令如下：",
  "",
  " - 我的排队",
  " - 查看队列",
  " - 我的资料",
  " - 修改资料",
  " - 排队通知",
  "",
  "有关玩家资料的命令，需要在机厅终端创建资料并设置 QQ 后才能使用。",
].join("\n");

export const PROFILE_EDIT_HELP_TEXT = [
  "可用的命令如下：",
  "",
  " - 修改昵称",
  " - 修改性别",
  " - 修改默认偏好",
  "",
  "发送其中一条命令后，请按提示完成修改。",
  "资料修改需要由机厅终端确认。",
].join("\n");

export interface Config {
  apiBase: string;
  botToken: string;
  oneBotSelfId?: string;
  notificationEnabled: boolean;
  notificationIntervalSeconds: number;
  commandWaitSeconds: number;
}

export const Config: Schema<Config> = Schema.object({
  apiBase: Schema.string()
    .role("link")
    .default("https://abcccc.top")
    .description("排队服务后端地址，不包含 /api 路径。"),
  botToken: Schema.string()
    .role("secret")
    .required()
    .description("服务器 QUEUE_BOT_TOKEN，不能与终端同步令牌相同。"),
  oneBotSelfId: Schema.string()
    .description(
      "用于发送私信的 OneBot 机器人 QQ；留空时使用首个在线 OneBot。",
    ),
  notificationEnabled: Schema.boolean()
    .default(true)
    .description("向好友发送本人相关的排队事件通知。"),
  notificationIntervalSeconds: Schema.number()
    .min(2)
    .max(60)
    .step(1)
    .default(5)
    .description("通知轮询间隔。"),
  commandWaitSeconds: Schema.number()
    .min(3)
    .max(60)
    .step(1)
    .default(15)
    .description("资料修改命令等待终端确认的时间。"),
});

export class QueueApi {
  constructor(private ctx: Context, private config: Config) {}

  getQueue(): Promise<QueueStatus> {
    return this.ctx.http.get(this.url("/api/queue-status"));
  }

  getPlayers(qq: string): Promise<BotPlayersResponse> {
    return this.privatePost("/api/queue-bot/players", { qq });
  }

  getProfiles(qq: string): Promise<ProfilesResponse> {
    return this.privatePost("/api/queue-bot/profiles", { qq });
  }

  getEvents(after: number, limit = 100): Promise<EventsResponse> {
    return this.privateGet("/api/queue-bot/events", { after, limit });
  }

  async updateProfile(
    profileId: string,
    actorQq: string,
    update: Partial<
      Pick<PlayerProfile, "nickname" | "gender" | "default_preference">
    >,
  ): Promise<RemoteCommand> {
    const response = await this.ctx.http<RemoteCommand | { error?: string }>(
      "PATCH",
      this.url(`/api/queue-bot/profiles/${encodeURIComponent(profileId)}`),
      {
        data: { request_id: randomUUID(), actor_qq: actorQq, ...update },
        headers: this.privateHeaders(),
        validateStatus: () => true,
      },
    );
    if (response.status >= 400) {
      throw new Error(
        profileUpdateErrorMessage(response.data, response.status),
      );
    }
    return response.data as RemoteCommand;
  }

  getCommand(commandId: string): Promise<RemoteCommand> {
    return this.privateGet(
      `/api/queue-bot/commands/${encodeURIComponent(commandId)}`,
    );
  }

  privateGet<T>(
    path: string,
    params?: Record<string, string | number>,
  ): Promise<T> {
    return this.ctx.http.get(this.url(path), {
      headers: this.privateHeaders(),
      params,
    });
  }

  privatePost<T>(path: string, body: Record<string, string>): Promise<T> {
    return this.ctx.http.post(this.url(path), body, {
      headers: this.privateHeaders(),
    });
  }

  privateHeaders(): Record<string, string> {
    return { Authorization: `Bearer ${this.config.botToken}` };
  }

  url(path: string): string {
    return new URL(path, this.config.apiBase).toString();
  }
}

export function apply(ctx: Context, config: Config) {
  const configError = apiBaseValidationError(config.apiBase);
  if (configError) throw new Error(configError);
  const logger = ctx.logger(name);
  const api = new QueueApi(ctx, config);

  ctx.model.extend("maimai_q_state", {
    key: "string",
    value: "text",
  }, { primary: "key" });

  ctx.model.extend("maimai_q_delivery", {
    queueId: "string",
    eventId: "string",
    qqNumber: "string",
    status: "string",
    attempts: "unsigned",
    nextRetryAt: "double",
    lastError: "text",
    updatedAt: "double",
  }, {
    primary: ["queueId", "eventId", "qqNumber"],
    indexes: [["queueId", "eventId"]],
  });

  ctx.command("maimaiq", "现场排队服务")
    .alias("排队")
    .action(() => HELP_TEXT);

  ctx.middleware((session, next) => {
    if (isOnlyBotMention(session)) return HELP_TEXT;
    return next();
  }, true);

  ctx.command("maimaiq.status", "查询自己的排队状态")
    .alias("我的排队")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const playerResponse = await api.getPlayers(qq);
        const players = playerResponse.players;
        if (players.length) {
          const queue = await api.getQueue().catch(() => undefined);
          const matchingQueue = queue?.queue_id === playerResponse.queue_id
            ? queue
            : undefined;
          return formatOwnQueue(players, matchingQueue);
        }
        const profiles = (await api.getProfiles(qq)).profiles;
        return profiles.length
          ? "你已有玩家资料，但当前没有正在排队的登记。"
          : "当前 QQ 尚未绑定终端中的玩家资料。请先在机厅终端创建资料。";
      })
    );

  ctx.command("maimaiq.queue", "查看完整队列")
    .alias("查看队列")
    .action(async () =>
      withCommandError(async () => {
        const queue = await api.getQueue();
        if (queue.onebot_sync_enabled === false) {
          throw new Error("现场终端已关闭 QQ Bot 联动。");
        }
        return formatQueue(queue);
      })
    );

  ctx.command("maimaiq.profile", "查看自己的玩家资料")
    .alias("我的资料")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profile = requireSingleProfile(
          (await api.getProfiles(qq)).profiles,
        );
        return formatProfile(profile);
      })
    );
  ctx.command("maimaiq.profile.edit", "打开玩家资料修改菜单")
    .alias("修改资料")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profiles = (await api.getProfiles(qq)).profiles;

        if (!profiles.length) {
          return "当前 QQ 尚未绑定玩家资料，不能使用资料修改功能。请先在机厅终端创建玩家资料。";
        }

        requireSingleProfile(profiles);
        return PROFILE_EDIT_HELP_TEXT;
      })
    );

  ctx.command("maimaiq.profile.nickname [nickname:text]", "修改玩家资料昵称")
    .alias("修改昵称")
    .action(async ({ session }, nickname) =>
      withCommandError(async () => {
        const value = await resolveProfileCommandInput(
          session,
          nickname,
          "请输入新的昵称。",
        );
        const validationError = nicknameValidationError(value);
        if (validationError) return validationError;
        return submitProfileUpdate(api, config, session, { nickname: value });
      })
    );

  ctx.command("maimaiq.profile.gender [gender:string]", "修改玩家资料性别")
    .alias("修改性别")
    .action(async ({ session }, gender) =>
      withCommandError(async () => {
        const input = await resolveProfileCommandInput(
          session,
          gender,
          "请输入“男”“女”或“不愿透露”。",
        );
        const value = parseGender(input);
        if (!value) return "请输入“男”“女”或“不愿透露”。";
        return submitProfileUpdate(api, config, session, { gender: value });
      })
    );

  ctx.command(
    "maimaiq.profile.preference [preference:text]",
    "修改玩家资料默认游玩偏好",
  )
    .alias("修改默认偏好")
    .action(async ({ session }, preference) =>
      withCommandError(async () => {
        const input = await resolveProfileCommandInput(
          session,
          preference,
          "请输入“单人游玩”“允许他人加入”或“每次询问”。",
        );
        const value = parsePreference(input);
        if (!value) return "请输入“单人游玩”“允许他人加入”或“每次询问”。";
        return submitProfileUpdate(api, config, session, {
          default_preference: value,
        });
      })
    );

  ctx.command("maimaiq.notifications [state:string]", "查看或调整排队通知")
    .alias("排队通知")
    .action(async ({ session }, state) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profiles = (await api.getProfiles(qq)).profiles;
        if (!profiles.length) {
          return "当前 QQ 尚未绑定玩家资料，不能调整排队通知。请先在机厅终端创建玩家资料。";
        }
        const requested = parseNotificationPreference(state);
        if (state?.trim() && requested === null) {
          return "请输入“开启”或“关闭”，也可以发送“开启排队通知”或“关闭排队通知”。";
        }
        if (requested !== null) {
          await writeNotificationPreference(ctx, config, qq, requested);
          return formatNotificationPreferenceChanged(
            requested,
            config.notificationEnabled,
          );
        }
        const enabled = await readNotificationPreference(ctx, config, qq);
        return formatNotificationPreferenceMenu(
          enabled,
          config.notificationEnabled,
        );
      })
    );

  ctx.command("maimaiq.notifications.enable", "开启排队通知")
    .alias("开启排队通知")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = await requireNotificationProfile(api, session);
        await writeNotificationPreference(ctx, config, qq, true);
        return formatNotificationPreferenceChanged(true, config.notificationEnabled);
      })
    );

  ctx.command("maimaiq.notifications.disable", "关闭排队通知")
    .alias("关闭排队通知")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = await requireNotificationProfile(api, session);
        await writeNotificationPreference(ctx, config, qq, false);
        return formatNotificationPreferenceChanged(false, config.notificationEnabled);
      })
    );

  if (config.notificationEnabled) {
    let polling = false;
    const poll = async () => {
      if (polling) return;
      polling = true;
      try {
        await pollNotifications(ctx, api, config, logger);
      } catch (error) {
        logger.warn("拉取排队通知失败：%s", apiErrorMessage(error));
      } finally {
        polling = false;
      }
    };
    ctx.on("ready", poll);
    ctx.setInterval(poll, config.notificationIntervalSeconds * 1_000);
  }
}

export async function resolveProfileCommandInput(
  session: Session | undefined,
  suppliedValue: string | undefined,
  instruction: string,
): Promise<string> {
  requireQqSession(session);
  const inlineValue = suppliedValue?.trim();
  if (inlineValue) return inlineValue;
  if (!session) throw new Error("当前会话不可用，请重新发送命令。");

  await session.send(
    `${instruction}\n请在 60 秒内回复；发送“取消”可结束本次修改。`,
  );
  const reply = (await session.prompt(PROFILE_INPUT_TIMEOUT_MS)).trim();
  if (!reply) {
    throw new Error("等待输入已结束。这次修改没有提交，请重新发送命令。");
  }
  if (reply === "取消") throw new Error("已取消这次修改。");
  return reply;
}

async function submitProfileUpdate(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
  update: Partial<
    Pick<PlayerProfile, "nickname" | "gender" | "default_preference">
  >,
): Promise<string> {
  const qq = requireQqSession(session);
  const profile = requireSingleProfile((await api.getProfiles(qq)).profiles);
  let command = await api.updateProfile(profile.profile_id, qq, update);
  const deadline = Date.now() + config.commandWaitSeconds * 1_000;
  while (command.status === "PENDING" && Date.now() < deadline) {
    await sleep(1_000);
    command = await api.getCommand(command.command_id);
  }
  const preferenceNote = update.default_preference
    ? "\n默认游玩偏好只用于以后加入排队，不会改变当前登记的本次偏好。"
    : "";
  if (command.status === "APPLIED") {
    return `玩家资料已经由机厅终端确认并更新。${preferenceNote}`;
  }
  if (command.status === "REJECTED") {
    return command.result_detail || "机厅终端拒绝了这次资料修改。";
  }
  return `修改已经提交，正在等待机厅终端确认。稍后可使用“我的资料”查看结果。${preferenceNote}`;
}

export async function pollNotifications(
  ctx: Context,
  api: QueueApi,
  config: Config,
  logger: ReturnType<Context["logger"]>,
) {
  const stateKey = cursorStateKey(config);
  const saved = await readCursor(ctx, stateKey);
  const firstPage = await api.getEvents(saved?.cursor ?? 0);
  if (!saved || saved.queueId !== firstPage.queue_id) {
    if (saved) {
      await ctx.database.remove("maimai_q_delivery", {
        queueId: saved.queueId,
      });
    }
    await ctx.database.remove("maimai_q_delivery", {
      queueId: firstPage.queue_id,
    });
    await writeCursor(ctx, stateKey, {
      queueId: firstPage.queue_id,
      cursor: firstPage.latest_cursor,
    });
    return;
  }

  let page = firstPage;
  let cursorBlocked = false;
  let selectedBot = selectOneBot(ctx, config.oneBotSelfId);
  const logState = { unavailableBotLogged: false };
  while (true) {
    for (const event of page.events) {
      const complete = await processNotificationEvent(
        ctx,
        api,
        logger,
        page.queue_id,
        event,
        selectedBot,
        logState,
        config,
      );
      if (!complete) {
        cursorBlocked = true;
        selectedBot = selectOneBot(ctx, config.oneBotSelfId);
        continue;
      }
      if (cursorBlocked) continue;

      await writeCursor(ctx, stateKey, {
        queueId: page.queue_id,
        cursor: event.cursor,
      });
      await ctx.database.remove("maimai_q_delivery", {
        queueId: page.queue_id,
        eventId: event.event_id,
        status: "DELIVERED",
      });
    }
    if (!page.has_more) break;
    const previousPageCursor = page.next_cursor;
    page = await api.getEvents(page.next_cursor);
    if (page.next_cursor <= previousPageCursor && page.has_more) {
      logger.warn("排队通知游标没有继续向前，已暂停本轮读取。");
      break;
    }
  }
}

async function processNotificationEvent(
  ctx: Context,
  api: QueueApi,
  logger: ReturnType<Context["logger"]>,
  queueId: string,
  event: QueueEvent,
  bot: Bot | undefined,
  logState: { unavailableBotLogged: boolean },
  config: Config,
): Promise<boolean> {
  const currentRecipients = [
    ...new Set(
      event.affected_players.map((player) => player.qq_number),
    ),
  ];
  if (!currentRecipients.length) return true;

  let deliveries = await ctx.database.get("maimai_q_delivery", {
    queueId,
    eventId: event.event_id,
  });
  if (!deliveries.length) {
    const recipientPreferences = await Promise.all(
      currentRecipients.map(async (qqNumber) => ({
        qqNumber,
        enabled: await readNotificationPreference(ctx, config, qqNumber),
      })),
    );
    const enabledRecipients = recipientPreferences
      .filter((preference) => preference.enabled)
      .map((preference) => preference.qqNumber);
    if (!enabledRecipients.length) return true;
    const now = Date.now();
    await ctx.database.upsert(
      "maimai_q_delivery",
      enabledRecipients.map((qqNumber) => ({
        queueId,
        eventId: event.event_id,
        qqNumber,
        status: "PENDING" as const,
        attempts: 0,
        nextRetryAt: 0,
        lastError: "",
        updatedAt: now,
      })),
    );
    deliveries = await ctx.database.get("maimai_q_delivery", {
      queueId,
      eventId: event.event_id,
    });
  }

  for (const delivery of deliveries) {
    if (isNotificationDeliveryTerminal(delivery)) continue;
    if (!await readNotificationPreference(ctx, config, delivery.qqNumber)) {
      await writeNotificationDelivery(ctx, {
        ...delivery,
        status: "DELIVERED",
        nextRetryAt: 0,
        lastError: "",
        updatedAt: Date.now(),
      });
      continue;
    }
    const now = Date.now();
    if (!isNotificationDeliveryDue(delivery, now)) continue;

    const attempt = delivery.attempts + 1;
    try {
      if (!bot) {
        if (!logState.unavailableBotLogged) {
          logger.warn(
            "没有可用于发送私信的 OneBot 实例，未完成通知将按退避策略重试。",
          );
          logState.unavailableBotLogged = true;
        }
        throw new Error("当前没有可用的 OneBot 实例");
      }
      const current = await api.getPlayers(delivery.qqNumber);
      const status = current.players.length
        ? `\n\n${formatOwnQueue(current.players)}`
        : "";
      await bot.sendPrivateMessage(
        delivery.qqNumber,
        formatQueueNotification(event, status),
      );
      await writeNotificationDelivery(ctx, {
        ...delivery,
        status: "DELIVERED",
        attempts: attempt,
        nextRetryAt: 0,
        lastError: "",
        updatedAt: Date.now(),
      });
    } catch (error) {
      const safeError = redactQqNumber(
        apiErrorMessage(error),
        delivery.qqNumber,
      );
      const failure = nextNotificationFailure(delivery, Date.now(), safeError);
      await writeNotificationDelivery(ctx, failure);
      if (failure.status === "FAILED") {
        logger.warn(
          "排队通知已停止重试：事件 %s，QQ %s，共尝试 %d 次：%s",
          event.event_id,
          maskQqNumber(delivery.qqNumber),
          failure.attempts,
          failure.lastError,
        );
      } else {
        logger.debug(
          "排队通知发送失败，将重试：事件 %s，QQ %s，第 %d 次：%s",
          event.event_id,
          maskQqNumber(delivery.qqNumber),
          failure.attempts,
          failure.lastError,
        );
      }
    }
  }

  const updated = await ctx.database.get("maimai_q_delivery", {
    queueId,
    eventId: event.event_id,
  });
  return updated.length > 0 && updated.every(isNotificationDeliveryTerminal);
}

async function writeNotificationDelivery(
  ctx: Context,
  delivery: NotificationDelivery,
) {
  await ctx.database.upsert("maimai_q_delivery", [delivery]);
}

export function isNotificationDeliveryDue(
  delivery: NotificationDelivery,
  now: number,
): boolean {
  return delivery.status === "PENDING" && delivery.nextRetryAt <= now;
}

export function isNotificationDeliveryTerminal(
  delivery: NotificationDelivery,
): boolean {
  return delivery.status === "DELIVERED" || delivery.status === "FAILED";
}

export function nextNotificationFailure(
  delivery: NotificationDelivery,
  now: number,
  error: string,
): NotificationDelivery {
  const attempts = delivery.attempts + 1;
  const failed = attempts >= NOTIFICATION_MAX_ATTEMPTS;
  const delayIndex = Math.min(
    attempts - 1,
    NOTIFICATION_RETRY_DELAYS_MS.length - 1,
  );
  return {
    ...delivery,
    status: failed ? "FAILED" : "PENDING",
    attempts,
    nextRetryAt: failed ? 0 : now + NOTIFICATION_RETRY_DELAYS_MS[delayIndex],
    lastError: error.slice(0, 500),
    updatedAt: now,
  };
}

function maskQqNumber(qqNumber: string): string {
  if (qqNumber.length <= 4) return "****";
  return `${qqNumber.slice(0, 2)}${"*".repeat(qqNumber.length - 4)}${
    qqNumber.slice(-2)
  }`;
}

export function redactQqNumber(value: string, qqNumber: string): string {
  if (!qqNumber) return value;
  return value.split(qqNumber).join(maskQqNumber(qqNumber));
}

function selectOneBot(ctx: Context, selfId?: string): Bot | undefined {
  return ctx.bots.find((bot) =>
    bot.platform === "onebot" &&
    bot.isActive &&
    (!selfId || bot.selfId === selfId)
  );
}

function cursorStateKey(config: Config): string {
  return `event-cursor:${new URL(config.apiBase).origin}`;
}

async function readCursor(
  ctx: Context,
  key: string,
): Promise<EventCursor | null> {
  const rows = await ctx.database.get("maimai_q_state", { key });
  if (!rows.length) return null;
  try {
    return JSON.parse(rows[0].value) as EventCursor;
  } catch {
    return null;
  }
}

async function writeCursor(ctx: Context, key: string, cursor: EventCursor) {
  await ctx.database.upsert("maimai_q_state", [{
    key,
    value: JSON.stringify(cursor),
  }]);
}

function notificationPreferenceStateKey(config: Config, qqNumber: string): string {
  return `notification-preference:${new URL(config.apiBase).origin}:${qqNumber}`;
}

export async function readNotificationPreference(
  ctx: Context,
  config: Config,
  qqNumber: string,
): Promise<boolean> {
  const rows = await ctx.database.get("maimai_q_state", {
    key: notificationPreferenceStateKey(config, qqNumber),
  });
  return rows[0]?.value !== "disabled";
}

export async function writeNotificationPreference(
  ctx: Context,
  config: Config,
  qqNumber: string,
  enabled: boolean,
) {
  await ctx.database.upsert("maimai_q_state", [{
    key: notificationPreferenceStateKey(config, qqNumber),
    value: enabled ? "enabled" : "disabled",
  }]);
}

export function parseNotificationPreference(value?: string): boolean | null {
  switch (value?.trim()) {
    case "开启":
    case "打开":
    case "启用":
      return true;
    case "关闭":
    case "停用":
      return false;
    default:
      return null;
  }
}

function formatNotificationPreferenceMenu(
  enabled: boolean,
  systemEnabled: boolean,
): string {
  return [
    "排队通知",
    "",
    `个人设置：${enabled ? "已开启" : "已关闭"}`,
    `系统通知：${systemEnabled ? "正在运行" : "暂未启用"}`,
    "",
    "开启后，与你有关的排队变动会通过 QQ 私聊发送。",
    "",
    " - 开启排队通知",
    " - 关闭排队通知",
  ].join("\n");
}

function formatNotificationPreferenceChanged(
  enabled: boolean,
  systemEnabled: boolean,
): string {
  if (!enabled) {
    return "排队通知已关闭。\n\n之后发生的个人排队变动不会再通过私聊发送；你仍可随时重新开启。";
  }
  return systemEnabled
    ? "排队通知已开启。\n\n之后发生的个人排队变动会通过私聊发送。"
    : "排队通知的个人设置已开启。\n\n系统通知目前暂未启用；系统恢复后，将按照这项设置发送。";
}

async function requireNotificationProfile(
  api: QueueApi,
  session: Session | undefined,
): Promise<string> {
  const qq = requireQqSession(session);
  if (!(await api.getProfiles(qq)).profiles.length) {
    throw new Error(
      "当前 QQ 尚未绑定玩家资料，不能调整排队通知。请先在机厅终端创建玩家资料。",
    );
  }
  return qq;
}

export function isOnlyBotMention(
  session: Pick<Session, "platform" | "selfId" | "elements">,
): boolean {
  if (session.platform !== "onebot" || !session.selfId) return false;
  const meaningfulElements = (session.elements ?? []).filter((element) => {
    if (element.type !== "text") return true;
    return String(element.attrs.content ?? "").trim().length > 0;
  });
  if (meaningfulElements.length !== 1) return false;
  const mention = meaningfulElements[0];
  return mention.type === "at" && String(mention.attrs.id ?? "") === session.selfId;
}

export function formatQueue(queue: QueueStatus): string {
  const terminalStatus = queue.terminal.online ? "终端在线" : "终端离线";
  const outsideBusinessHours = queue.business_hours?.enabled &&
    queue.business_hours.outside;
  const queueMode = outsideBusinessHours
    ? "·不在营业时间"
    : queue.registration_open
    ? ""
    : "·自然排队";
  const lines = [`当前队列·${terminalStatus}${queueMode}`];
  if (queue.terminal.online) {
    lines.push("");
  } else {
    lines.push("以下为最近一次同步状态。", "");
  }
  if (queue.business_hours?.closing_grace) {
    lines.push(
      "今日营业时间已结束",
      "不再接收新登记。现有队列处理完毕后将关闭，最迟保留 20 分钟。",
      "",
    );
  }
  for (const machine of [queue.machines.A, queue.machines.B]) {
    lines.push(formatMachine(machine), "");
  }
  return lines.join("\n").trimEnd();
}

function formatMachine(machine: QueueMachine): string {
  const waitingCount = machine.waiting_positions.reduce(
    (total, position) => total + position.registrations.length,
    0,
  );
  const registrationCount = machine.playing.length + waitingCount;
  const compactMachineName = machine.name.replace(/\s*·\s*/g, "·");
  const overview = [`【${compactMachineName}】`];
  if (!machine.operational) {
    overview.push(
      `停止使用${
        machine.stop_reason ? `·${stopReason(machine.stop_reason)}` : ""
      }`,
    );
  }
  if (registrationCount) {
    overview.push(
      `${machine.waiting_positions.length} 个等待位置·${registrationCount} 个登记`,
    );
  }
  if (!machine.operational) {
    if (registrationCount) {
      overview.push("登记顺序已保留，恢复使用后继续排队。");
    }
  }

  const sections = [overview];
  if (machine.playing.length) {
    const elapsed = machine.operational && machine.playing_started_at
      ? Math.max(
        0,
        Math.floor((Date.now() - machine.playing_started_at) / 60_000),
      )
      : null;
    const playingState = machine.operational
      ? elapsed === null ? "" : `·${elapsed} 分钟`
      : "·已暂停";
    sections.push(
      [
        `游玩位置 ${machine.id}${playingState}`,
        ...formatQueueRegistrations(machine.playing),
      ],
    );
  } else {
    sections.push([`游玩位置 ${machine.id}·空闲`]);
  }
  for (const position of machine.waiting_positions) {
    const estimate = machine.operational &&
        position.estimated_wait_minutes !== null
      ? position.estimated_wait_minutes <= 0
        ? "·即将游玩"
        : `·约 ${position.estimated_wait_minutes} 分钟后`
      : "";
    sections.push(
      [
        `位置 ${machine.id}${position.index}${estimate}`,
        ...formatQueueRegistrations(position.registrations),
      ],
    );
  }
  return sections.map((section) => section.join("\n")).join("\n\n");
}

export function formatOwnQueue(
  players: BotPlayer[],
  queue?: QueueStatus,
): string {
  if (!players.length) return "当前没有正在排队的登记。";
  const status = players.map((player) => {
    const machine = queue?.machines[player.machine_id];
    const registration = machine
      ? [
        ...machine.playing,
        ...machine.waiting_positions.flatMap((position) =>
          position.registrations
        ),
      ]
        .find((item) => item.registration_id === player.registration_id)
      : undefined;
    const elapsed = player.position === "PLAYING" && machine?.operational &&
        machine.playing_started_at
      ? Math.max(
        0,
        Math.floor((Date.now() - machine.playing_started_at) / 60_000),
      )
      : null;
    const location = player.position === "PLAYING"
      ? `正在游玩位置 ${player.machine_id}${
        elapsed === null ? "" : `·已游玩 ${elapsed} 分钟`
      }`
      : `位于队列位置 ${player.machine_id}${player.position_index}`;
    const estimate = player.position === "WAITING" &&
        machine?.operational !== false &&
        player.estimated_wait_minutes !== null
      ? `，约 ${player.estimated_wait_minutes} 分钟后可以游玩`
      : "";
    const machineState = machine?.operational === false
      ? "\n机台状态：停止使用，登记顺序已保留。"
      : "";
    const states = [];
    if (player.deferred_once) states.push("暂缓一轮");
    if (player.temporarily_away) {
      states.push(
        `暂时离开${
          player.temporary_away_skipped_turns
            ? `·已轮空 ${player.temporary_away_skipped_turns} 次`
            : ""
        }`,
      );
    }
    if (player.no_show_count) {
      const lastAction = player.last_no_show_action_was_defer
        ? "上次处理：暂缓一轮"
        : "上次处理：移至队尾";
      states.push(`未到场记录 ${player.no_show_count} 次·${lastAction}`);
    }
    const preference = registration
      ? `\n本次偏好：${queuePreferenceLabel(registration.preference)}`
      : "";
    return `${player.display_id}：${location}${estimate}${machineState}${preference}${
      states.length ? `\n当前状态：${states.join("、")}` : ""
    }`;
  }).join("\n\n");
  if (queue && !queue.terminal.online) {
    return `终端暂时离线，以下为最近一次同步状态。\n\n${status}`;
  }
  return status;
}

export function formatQueueNotification(
  event: Pick<QueueEvent, "title" | "detail">,
  status = "",
): string {
  return compactMiddleDots(
    `【排队通知】\n\n${event.title}\n${event.detail}${status}`,
  );
}

function compactMiddleDots(value: string): string {
  return value.replace(/\s*·\s*/g, "·");
}

function formatProfile(profile: PlayerProfile): string {
  return [
    `玩家资料：${profile.nickname}`,
    `性别：${genderLabel(profile.gender)}`,
    `默认偏好：${preferenceLabel(profile.default_preference)}`,
    `使用次数：${profile.usage_count}`,
    "",
    "默认偏好只用于以后加入排队，不会改变当前登记的本次偏好。",
  ].join("\n");
}

function formatQueueRegistrations(
  registrations: QueueRegistration[],
): string[] {
  return registrations.length
    ? registrations.flatMap(formatQueueRegistration)
    : ["暂无登记"];
}

function formatQueueRegistration(registration: QueueRegistration): string[] {
  const lines = [
    ` - ${registration.display_id} (${
      compactQueuePreferenceLabel(registration.preference)
    })`,
  ];
  if (registration.deferred_once) lines.push("    - 暂缓一轮");
  if (registration.temporarily_away) {
    lines.push(
      `    - 暂时离开${
        registration.temporary_away_skipped_turns
          ? `·已轮空 ${registration.temporary_away_skipped_turns} 次`
          : ""
      }`,
    );
  }
  if (registration.no_show_count) {
    lines.push(`    - 未到场记录 ${registration.no_show_count} 次`);
  }
  return lines;
}

function compactQueuePreferenceLabel(
  value: QueueRegistration["preference"],
): string {
  return value === "SOLO" ? "单人" : "允许加入";
}

function queuePreferenceLabel(value: QueueRegistration["preference"]): string {
  return value === "SOLO" ? "单人游玩" : "允许他人加入";
}

export function nicknameValidationError(value?: string): string | null {
  const nickname = value?.trim() ?? "";
  if (!nickname || [...nickname].length > 18) {
    return "昵称应为 1 至 18 个字符。";
  }
  if (/[\p{Cc}\p{Zl}\p{Zp}]/u.test(nickname)) {
    return "昵称不能包含换行或控制字符。";
  }
  return null;
}

export function parseGender(value?: string): PlayerGender | null {
  switch (value?.trim()) {
    case "男":
      return "MALE";
    case "女":
      return "FEMALE";
    case "不愿透露":
    case "不透露":
      return "UNDISCLOSED";
    default:
      return null;
  }
}

export function parsePreference(value?: string): ProfilePreference | null {
  switch (value?.trim()) {
    case "单人":
    case "单人游玩":
      return "SOLO";
    case "允许加入":
    case "允许他人加入":
      return "OPEN_TO_JOIN";
    case "每次询问":
      return "ASK_EVERY_TIME";
    default:
      return null;
  }
}

function genderLabel(value: PlayerGender): string {
  return { MALE: "男", FEMALE: "女", UNDISCLOSED: "不愿透露" }[value];
}

function preferenceLabel(value: ProfilePreference): string {
  return {
    SOLO: "单人游玩",
    OPEN_TO_JOIN: "允许他人加入",
    ASK_EVERY_TIME: "每次询问",
  }[value];
}

function stopReason(value: string): string {
  return {
    NOT_POWERED_ON: "机台未开机",
    NETWORK_DISCONNECTED: "机台断网",
    MAINTENANCE: "维护保养",
    OTHER: "其他原因",
  }[value] || "原因未注明";
}

export function requireQqSession(session: Session | undefined): string {
  if (!session) throw new Error("无法读取当前 QQ 会话。");
  const qq = session.userId;
  if (session.platform !== "onebot" || !qq || !/^[0-9]{5,12}$/.test(qq)) {
    throw new Error("这个功能只能由 OneBot QQ 用户使用。");
  }
  if (!session.isDirect) {
    throw new Error("为保护个人信息，请私聊机器人使用这个功能。");
  }
  return qq;
}

export function apiBaseValidationError(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return "apiBase 必须是有效的服务器地址。";
  }
  if (url.username || url.password) return "apiBase 不能包含用户名或密码。";
  if (url.pathname !== "/" || url.search || url.hash) {
    return "apiBase 应只填写站点根地址，不要包含路径、查询参数或片段。";
  }
  if (url.protocol === "https:") return null;
  const localHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (url.protocol === "http:" && localHosts.has(url.hostname.toLowerCase())) {
    return null;
  }
  return "apiBase 必须使用 HTTPS；只有 localhost、127.0.0.1 或 ::1 可以使用 HTTP。";
}

function requireSingleProfile(profiles: PlayerProfile[]): PlayerProfile {
  if (!profiles.length) throw new Error("当前 QQ 尚未绑定终端中的玩家资料。");
  if (profiles.length > 1) {
    throw new Error("当前 QQ 绑定了多份资料，请先联系现场工作人员处理。");
  }
  return profiles[0];
}

async function withCommandError(
  action: () => Promise<string>,
): Promise<string> {
  try {
    return await action();
  } catch (error) {
    return apiErrorMessage(error);
  }
}

function apiErrorMessage(error: unknown): string {
  const candidate = error as {
    message?: string;
    response?: { data?: { error?: string } };
  };
  return candidate.response?.data?.error || candidate.message ||
    "暂时无法连接排队服务。";
}

export function profileUpdateErrorMessage(
  data: unknown,
  status: number,
): string {
  if (data && typeof data === "object" && "error" in data) {
    const detail = (data as { error?: unknown }).error;
    if (typeof detail === "string" && detail.trim()) return detail.trim();
  }
  if (typeof data === "string" && data.trim()) {
    try {
      return profileUpdateErrorMessage(JSON.parse(data), status);
    } catch {
      const detail = data.trim();
      if (!detail.startsWith("<") && detail.length <= 200) return detail;
    }
  }
  return `服务器未接受这次资料修改（HTTP ${status}）。`;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
