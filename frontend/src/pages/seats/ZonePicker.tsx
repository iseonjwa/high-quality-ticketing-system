import type { CSSProperties } from 'react'
import { ToggleGroup } from 'radix-ui'
import type { ScheduleSeats } from '@/api/concerts'
import { gradeColor } from '@/components/ticketing/grade'
import { formatWon } from '@/lib/format'
import { remainingSeats } from '@/pages/seats/seatSections'

type Sections = ScheduleSeats['sections']

interface ZonePickerProps {
  sections: Sections
  current: string
  // 구역마다 고른 좌석 수. 구역을 옮겨도 고른 좌석이 남아 있다는 것을 배치도에서도 보인다
  pickedBySection: Map<string, number>
  onSelect: (section: string) => void
}

const count = new Intl.NumberFormat('ko-KR')

// 구역 배치도(samples/seat-sections.html). 응답에 구역 위치가 없어 응답 순서대로 줄마다 5칸(좁으면 4칸)씩 놓는다.
// Note: 하나만 고르는 단일 선택이라 radio로 읽힌다(Radix ToggleGroup single). Tab 정지점이 하나이고 화살표로 옮긴다.
// 구역이 25개라 구역마다 Tab 정지점을 두면 좌석까지 가는 길이 너무 길다.
export function ZonePicker({ sections, current, pickedBySection, onSelect }: ZonePickerProps) {
  return (
    <div className="grid gap-3">
      <div
        aria-hidden="true"
        className="mx-auto mb-2 grid h-9 w-3/5 place-items-center rounded-b-[40px] bg-gray-800 text-[13px] font-semibold tracking-[0.3em] text-white"
      >
        무대
      </div>
      <ToggleGroup.Root
        type="single"
        aria-label="구역"
        value={current}
        onValueChange={(next) => {
          // 고른 구역을 다시 누르면 빈 값이 온다. 구역은 늘 하나가 골라져 있어야 해서 무시한다
          if (next !== '') {
            onSelect(next)
          }
        }}
        className="grid grid-cols-4 gap-2 @min-[40rem]:grid-cols-5"
      >
        {sections.map((section) => {
          const left = remainingSeats(section)
          const picked = pickedBySection.get(section.section) ?? 0
          const name = [
            `${section.section}구역 ${section.grade}`,
            left > 0 ? `남은 ${count.format(left)}석` : '매진',
            ...(picked > 0 ? [`${picked}석 고름`] : []),
          ].join(', ')
          return (
            <ToggleGroup.Item
              key={section.section}
              value={section.section}
              disabled={left === 0}
              aria-label={name}
              style={{ '--c': gradeColor(section.grade) } as CSSProperties}
              className="group/zone relative grid min-h-[60px] content-start justify-items-start gap-0.5 rounded-md border border-gray-100 bg-gray-0 px-2 py-2 text-left text-gray-700 outline-none transition-colors duration-(--dur-fast) hover:border-gray-200 hover:bg-gray-50 focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:bg-gray-50 disabled:hover:border-gray-100 data-[state=on]:border-primary-600 data-[state=on]:bg-primary-50 data-[state=on]:shadow-[inset_0_0_0_1px_var(--primary-600)] @min-[40rem]:px-3 @min-[40rem]:py-2.5"
            >
              <span className="flex items-center gap-1.5 text-[16px] leading-[22px] font-extrabold text-gray-900 group-disabled/zone:text-gray-400">
                <span
                  aria-hidden="true"
                  className="size-2.5 shrink-0 rounded-[3px] bg-(--c) group-disabled/zone:bg-gray-200"
                />
                {section.section}
              </span>
              <span className="type-caption text-gray-500 tabular-nums">
                {left > 0 ? `남은 ${count.format(left)}` : '매진'}
              </span>
              {picked > 0 ? (
                <span
                  aria-hidden="true"
                  className="absolute top-1.5 right-1.5 grid h-5 min-w-5 place-items-center rounded-pill bg-gray-900 px-1.5 text-[11px] font-bold text-white tabular-nums @min-[40rem]:top-2 @min-[40rem]:right-2"
                >
                  {picked}
                </span>
              ) : null}
            </ToggleGroup.Item>
          )
        })}
      </ToggleGroup.Root>
      <ZoneLegend sections={sections} />
    </div>
  )
}

// 등급마다 색과 가격. 구역 칸에는 등급 색만 있어 색만으로 등급을 알리지 않도록 글자로도 적는다
function ZoneLegend({ sections }: { sections: Sections }) {
  const grades = new Map<string, number>()
  for (const { grade, price } of sections) {
    if (!grades.has(grade)) {
      grades.set(grade, price)
    }
  }
  return (
    <ul
      aria-label="등급별 가격"
      className="flex flex-wrap gap-x-5 gap-y-2 text-[13px] text-gray-500"
    >
      {[...grades].map(([grade, price]) => (
        <li key={grade} className="inline-flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className="size-2.5 rounded-[3px]"
            style={{ background: gradeColor(grade) }}
          />
          {grade}
          <b className="font-semibold text-gray-900 tabular-nums">{formatWon(price)}</b>
        </li>
      ))}
    </ul>
  )
}
