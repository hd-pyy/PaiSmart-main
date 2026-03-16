import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);

  const store = useAuthStore();

  // 使用 computed 动态生成 WebSocket URL，确保 token 有值
  const wsUrl = computed(() => {
    const token = store.token || '';
    return token ? `/proxy-ws/chat/${token}` : '';
  });

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(wsUrl, {
    autoReconnect: true,
    immediate: false // 不立即连接，等待 token 有值
  });

  // 监听 token 变化，有 token 时才打开连接
  watch(() => store.token, (newToken) => {
    if (newToken && wsUrl.value) {
      wsOpen();
    } else {
      wsClose();
    }
  }, { immediate: true });

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
    list,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom
  };
});
