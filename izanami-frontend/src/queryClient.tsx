import { QueryClient } from '@tanstack/react-query';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false, // TODO for dev only
      refetchOnWindowFocus: false, // TODO for dev only
    },
  },
});

export default queryClient;
