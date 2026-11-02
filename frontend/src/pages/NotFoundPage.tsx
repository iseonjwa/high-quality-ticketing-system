import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-svh max-w-3xl flex-col justify-center gap-4 px-6">
      <h1 className="text-2xl font-bold">페이지를 찾을 수 없어요</h1>
      <p className="text-muted-foreground">주소를 다시 확인하거나 처음 화면으로 돌아가 주세요.</p>
      <div>
        <Button asChild>
          <Link to="/">처음으로</Link>
        </Button>
      </div>
    </main>
  )
}
