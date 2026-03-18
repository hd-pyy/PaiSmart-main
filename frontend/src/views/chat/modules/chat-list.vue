<script setup lang="ts">
import { NScrollbar, NSelect, NButton, NForm, NFormItem, NDatePicker, NSpin } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessions, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const sessionLoading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

watch(conversationId, async (newVal) => {
  if (newVal) await getList();
});

async function loadSessions() {
  sessionLoading.value = true;
  const { error, data } = await request<Array<{ conversationId: string; name: string; createdAt: string }>>({
    url: 'users/conversation/sessions'
  });
  if (!error && data) {
    sessions.value = data;
    if (!conversationId.value && data.length > 0) {
      chatStore.conversationId = data[0].conversationId;
    }
  }
  sessionLoading.value = false;
}

async function newSession() {
  const { error, data } = await request<{ conversationId: string }>({
    url: 'users/conversation/session',
    method: 'POST'
  });
  if (!error && data?.conversationId) {
    await loadSessions();
    chatStore.conversationId = data.conversationId;
    await getList();
  }
}

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);

const params = computed(() => {
  return {
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };
});

async function getList() {
  if (!conversationId.value) {
    list.value = [];
    return;
  }
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: {
      ...params.value,
      conversation_id: conversationId.value
    }
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(async () => {
  await loadSessions();
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <Teleport defer to="#header-extra">
        <div class="px-10 flex flex-col gap-2">
          <div class="flex items-center gap-2">
            <NSelect
              v-model:value="conversationId"
              :options="sessions.map(item => ({ label: item.name || item.createdAt, value: item.conversationId }))"
              placeholder="选择会话"
              style="min-width: 180px"
              :disabled="sessionLoading"
            />
            <NButton size="small" type="primary" @click="newSession" :loading="sessionLoading">新建会话</NButton>
          </div>
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="时间">
              <NDatePicker v-model:value="range" type="daterange" />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
        </VueMarkdownItProvider>
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
