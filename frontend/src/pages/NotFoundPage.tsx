import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-4 py-12">
      <h1 className="type-title text-gray-900">페이지를 찾을 수 없어요</h1>
      <p className="text-muted-foreground">주소를 다시 확인하거나 처음 화면으로 돌아가 주세요.</p>
      <div>
        <Button asChild>
          <Link to="/">처음으로</Link>
        </Button>
      </div>
    </main>
  )
}
