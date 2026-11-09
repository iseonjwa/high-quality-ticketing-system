import { cn } from '@/lib/utils'

// 티켓 카드를 가로지르는 절취선(design-system.md 11절 티켓 모양). 양 끝 둥근 홈은 카드 뒤 바탕색으로 칠하므로
// 카드 양 끝까지 닿아야 한다. 카드에 안쪽 여백이 있으면 그만큼 음수 여백을 className으로 넘긴다(예: -mx-5).
// Note: 카드가 그림 위에 놓이면 홈이 바탕색 점으로 보인다. 그때는 .ticket-pass로 실제 구멍을 낸다
export function TicketPerforation({ className }: { className?: string }) {
  return (
    <div aria-hidden="true" className={cn(className)}>
      <span className="ticket-perf block" />
    </div>
  )
}
