export function getPageNumbers(page: number, totalPages: number): number[] {
  if (totalPages <= 1) return [0];
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i);
  }
  if (page < 3) {
    return [0, 1, 2, 3, -1, totalPages - 1];
  }
  if (page >= totalPages - 3) {
    return [0, -1, totalPages - 4, totalPages - 3, totalPages - 2, totalPages - 1];
  }
  return [0, -1, page - 1, page, page + 1, -1, totalPages - 1];
}
