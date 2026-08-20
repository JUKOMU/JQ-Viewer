import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { createCanvas } from '@napi-rs/canvas'

export const historySchemaVersion = 1
export const chartDayLimit = 14

function positiveInteger(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? Math.trunc(number) : 0
}

function shanghaiDate(value) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(value)
  const byType = Object.fromEntries(parts.map((part) => [part.type, part.value]))

  return `${byType.year}-${byType.month}-${byType.day}`
}

export function createVisitSample(releases, sampledAt = new Date()) {
  return {
    date: shanghaiDate(sampledAt),
    releases: releases.map((release) => ({
      id: String(release.id),
      tagName: release.tag_name,
      assets: (release.assets || []).map((asset) => ({
        id: String(asset.id),
        name: asset.name,
        downloadCount: positiveInteger(asset.download_count),
      })),
    })),
  }
}

export function mergeVisitHistory(history, sample) {
  const samples = [...(history?.samples || []).filter((item) => item.date !== sample.date), sample]
  samples.sort((left, right) => left.date.localeCompare(right.date))

  return {
    schemaVersion: historySchemaVersion,
    samples,
  }
}

export function totalDownloads(sample) {
  return sample.releases.reduce(
    (releaseTotal, release) =>
      releaseTotal +
      release.assets.reduce((assetTotal, asset) => assetTotal + asset.downloadCount, 0),
    0,
  )
}

export function buildChartPoints(history, limit = chartDayLimit) {
  const allPoints = history.samples.map((sample, index) => {
    const total = totalDownloads(sample)
    const previousTotal = index === 0 ? 0 : totalDownloads(history.samples[index - 1])

    return {
      date: sample.date,
      increment: index === 0 ? total : Math.max(0, total - previousTotal),
      total,
    }
  })

  return allPoints.slice(-limit)
}

export function formatVisitValue(value) {
  return value >= 1000
    ? `${(Math.round(value / 100) / 10).toFixed(1)} k`
    : String(Math.round(value))
}

function niceMaximum(value) {
  if (value <= 0) {
    return 1
  }

  const magnitude = 10 ** Math.floor(Math.log10(value))
  const normalized = value / magnitude
  const multiplier = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10

  return multiplier * magnitude
}

function drawText(context, text, x, y, options = {}) {
  context.fillStyle = options.color || '#334155'
  context.font = options.font || '24px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif'
  context.textAlign = options.align || 'left'
  context.textBaseline = options.baseline || 'alphabetic'
  context.fillText(text, x, y)
}

function chartDates(points) {
  if (points.length <= 7) {
    return points.map((_, index) => index)
  }

  const lastIndex = points.length - 1
  return Array.from(
    new Set([0, 2, 4, 6, 8, 10, 12, lastIndex].filter((index) => index <= lastIndex)),
  )
}

export function renderVisitChart(points, outputPath) {
  const width = 1200
  const height = 520
  const plot = { left: 92, right: 1108, top: 112, bottom: 418 }
  const canvas = createCanvas(width, height)
  const context = canvas.getContext('2d')
  const plotWidth = plot.right - plot.left
  const plotHeight = plot.bottom - plot.top
  const incrementMaximum = niceMaximum(Math.max(...points.map((point) => point.increment), 0))
  const totalMaximum = niceMaximum(Math.max(...points.map((point) => point.total), 0))
  const ticks = 4
  const xFor = (index) =>
    points.length <= 1
      ? (plot.left + plot.right) / 2
      : plot.left + (plotWidth * index) / (points.length - 1)
  const yFor = (value, maximum) => plot.bottom - (value / maximum) * plotHeight

  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, width, height)

  drawText(context, '访问量统计', plot.left, 54, {
    color: '#1e293b',
    font: '700 28px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  })
  drawText(context, '新增访问量', plot.left, 86, {
    color: '#64748b',
    font: '20px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  })
  drawText(context, '累计访问量', plot.right, 86, {
    align: 'right',
    color: '#64748b',
    font: '20px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  })

  for (let index = 0; index <= ticks; index += 1) {
    const ratio = index / ticks
    const y = plot.bottom - ratio * plotHeight

    context.strokeStyle = '#e2e8f0'
    context.lineWidth = 1
    context.beginPath()
    context.moveTo(plot.left, y)
    context.lineTo(plot.right, y)
    context.stroke()

    drawText(context, formatVisitValue(incrementMaximum * ratio), plot.left - 14, y, {
      align: 'right',
      baseline: 'middle',
      color: '#94a3b8',
      font: '16px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
    })
    drawText(context, formatVisitValue(totalMaximum * ratio), plot.right + 14, y, {
      baseline: 'middle',
      color: '#94a3b8',
      font: '16px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
    })
  }

  const barWidth = Math.min(12, points.length <= 1 ? 12 : (plotWidth / (points.length - 1)) * 0.24)
  context.fillStyle = '#8ecae6'
  points.forEach((point, index) => {
    const x = xFor(index)
    const y = yFor(point.increment, incrementMaximum)
    context.fillRect(x - barWidth / 2, y, barWidth, plot.bottom - y)
  })

  if (points.length > 0) {
    context.strokeStyle = '#e76f51'
    context.lineWidth = 3
    context.beginPath()
    points.forEach((point, index) => {
      const x = xFor(index)
      const y = yFor(point.total, totalMaximum)
      if (index === 0) {
        context.moveTo(x, y)
      } else {
        context.lineTo(x, y)
      }
    })
    context.stroke()

    context.fillStyle = '#e76f51'
    points.forEach((point, index) => {
      context.beginPath()
      context.arc(xFor(index), yFor(point.total, totalMaximum), 4, 0, Math.PI * 2)
      context.fill()
    })
  }

  for (const index of chartDates(points)) {
    drawText(context, points[index].date.slice(5), xFor(index), plot.bottom + 30, {
      align: 'center',
      color: '#64748b',
      font: '16px "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
    })
  }

  fs.mkdirSync(path.dirname(outputPath), { recursive: true })
  fs.writeFileSync(outputPath, canvas.toBuffer('image/png'))
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'))
}

function main() {
  const [command, releasesPath, historyPath, outputHistoryPath, outputImagePath] =
    process.argv.slice(2)
  if (
    command !== 'update' ||
    !releasesPath ||
    !historyPath ||
    !outputHistoryPath ||
    !outputImagePath
  ) {
    throw new Error(
      'usage: node scripts/release-visits.mjs update <releases-json> <history-json> <history-output> <image-output>',
    )
  }

  const releasePages = readJson(releasesPath)
  const releases = Array.isArray(releasePages[0]) ? releasePages.flat() : releasePages
  const history = readJson(historyPath)
  const nextHistory = mergeVisitHistory(history, createVisitSample(releases))

  fs.mkdirSync(path.dirname(outputHistoryPath), { recursive: true })
  fs.writeFileSync(outputHistoryPath, JSON.stringify(nextHistory, null, 2) + '\n')
  renderVisitChart(buildChartPoints(nextHistory), outputImagePath)
}

const thisFile = fileURLToPath(import.meta.url)
if (path.resolve(process.argv[1] || '') === thisFile) {
  main()
}
