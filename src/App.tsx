import React, { useState, useEffect } from 'react';
import {
  Download,
  Play,
  Pause,
  RotateCcw,
  Trash2,
  Settings,
  ListVideo,
  Search,
  CheckCircle2,
  AlertCircle,
  Clock,
  Film,
  Music,
  Scissors,
  Copy,
  ExternalLink,
  ChevronRight,
  Sparkles,
  Layers,
  HardDrive,
  Cpu,
  Terminal,
  FolderDown,
  Volume2,
  VolumeX,
  Maximize2,
  Eye,
  Calendar,
  User,
  Globe,
  Sun,
  Moon,
  Info
} from 'lucide-react';

interface VideoMetadata {
  id: string;
  url: string;
  title: string;
  author: string;
  authorAvatar?: string;
  duration: string;
  durationSeconds: number;
  thumbnail: string;
  views: string;
  uploadedAt: string;
  description: string;
}

interface FormatOption {
  id: string;
  label: string;
  ext: string;
  resolution?: string;
  bitrate?: string;
  sizeEstimate: string;
  type: 'video' | 'audio';
  qualityBadge: string;
}

interface DownloadItem {
  id: string;
  url: string;
  title: string;
  thumbnail: string;
  format: FormatOption;
  progress: number;
  downloadedBytes: number;
  totalBytes: number;
  speed: string;
  eta: string;
  status: 'downloading' | 'completed' | 'paused' | 'failed';
  timeRange?: { start: string; end: string };
  createdAt: number;
  error?: string;
  localPath: string;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'home' | 'downloads' | 'player' | 'settings'>('home');
  const [lang, setLang] = useState<'ar' | 'en'>('ar');
  const [urlInput, setUrlInput] = useState('');
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [videoInfo, setVideoInfo] = useState<VideoMetadata | null>(null);
  const [selectedFormat, setSelectedFormat] = useState<FormatOption | null>(null);
  const [enableCut, setEnableCut] = useState(false);
  const [startTime, setStartTime] = useState('00:00:00');
  const [endTime, setEndTime] = useState('00:01:30');
  const [downloads, setDownloads] = useState<DownloadItem[]>([
    {
      id: 'dl-1',
      url: 'https://youtube.com/watch?v=sample1',
      title: 'تعلم تطوير تطبيقات أندرويد و Kotlin بأسلوب حديث 2026',
      thumbnail: 'https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=600&auto=format&fit=crop&q=80',
      format: {
        id: '1080p-mp4',
        label: '1080p Full HD',
        ext: 'MP4',
        resolution: '1920x1080',
        sizeEstimate: '142 MB',
        type: 'video',
        qualityBadge: 'HD'
      },
      progress: 100,
      downloadedBytes: 148897792,
      totalBytes: 148897792,
      speed: '0 KB/s',
      eta: '0s',
      status: 'completed',
      createdAt: Date.now() - 3600000,
      localPath: '/storage/emulated/0/Download/DownloadVideos/android_tutorial.mp4'
    },
    {
      id: 'dl-2',
      url: 'https://youtube.com/watch?v=sample2',
      title: 'أجمل التلاوات القرآنية بصوت نقي خاشع - MP3 عالية الجودة',
      thumbnail: 'https://images.unsplash.com/photo-1585036156171-384164a8c675?w=600&auto=format&fit=crop&q=80',
      format: {
        id: 'mp3-320',
        label: 'High Quality MP3 (320 kbps)',
        ext: 'MP3',
        bitrate: '320k',
        sizeEstimate: '45 MB',
        type: 'audio',
        qualityBadge: 'HQ'
      },
      progress: 68,
      downloadedBytes: 32000000,
      totalBytes: 47185920,
      speed: '4.8 MB/s',
      eta: '3s',
      status: 'downloading',
      createdAt: Date.now() - 60000,
      localPath: '/storage/emulated/0/Download/DownloadVideos/audio_quran.mp3'
    }
  ]);

  const [activePlayingItem, setActivePlayingItem] = useState<DownloadItem | null>(null);
  const [isPlaying, setIsPlaying] = useState(true);
  const [maxConcurrent, setMaxConcurrent] = useState(3);
  const [autoRetry, setAutoRetry] = useState(true);
  const [notifyBackground, setNotifyBackground] = useState(true);
  const [filterStatus, setFilterStatus] = useState<'all' | 'downloading' | 'completed' | 'failed'>('all');

  const sampleFormats: FormatOption[] = [
    { id: '1080p', label: '1080p Full HD', ext: 'MP4', resolution: '1920x1080', sizeEstimate: '158 MB', type: 'video', qualityBadge: '1080p' },
    { id: '720p', label: '720p HD', ext: 'MP4', resolution: '1280x720', sizeEstimate: '82 MB', type: 'video', qualityBadge: '720p' },
    { id: '480p', label: '480p SD', ext: 'MP4', resolution: '854x480', sizeEstimate: '45 MB', type: 'video', qualityBadge: '480p' },
    { id: '360p', label: '360p Low', ext: 'MP4', resolution: '640x360', sizeEstimate: '26 MB', type: 'video', qualityBadge: '360p' },
    { id: 'mp3-320', label: 'Audio MP3 - 320 kbps', ext: 'MP3', bitrate: '320 kbps', sizeEstimate: '18 MB', type: 'audio', qualityBadge: 'HQ' },
    { id: 'm4a-160', label: 'Audio M4A - 160 kbps', ext: 'M4A', bitrate: '160 kbps', sizeEstimate: '11 MB', type: 'audio', qualityBadge: 'M4A' },
    { id: 'wav', label: 'Audio Lossless WAV', ext: 'WAV', bitrate: '1411 kbps', sizeEstimate: '54 MB', type: 'audio', qualityBadge: 'WAV' },
  ];

  // Simulation timer for active downloads
  useEffect(() => {
    const timer = setInterval(() => {
      setDownloads(prev => prev.map(item => {
        if (item.status === 'downloading') {
          const nextProgress = Math.min(100, item.progress + 6);
          const nextDownloaded = Math.floor((nextProgress / 100) * item.totalBytes);
          const isDone = nextProgress >= 100;
          return {
            ...item,
            progress: nextProgress,
            downloadedBytes: nextDownloaded,
            status: isDone ? 'completed' : 'downloading',
            speed: isDone ? '0 KB/s' : `${(Math.random() * 2 + 3.2).toFixed(1)} MB/s`,
            eta: isDone ? '0s' : `${Math.max(1, Math.ceil((100 - nextProgress) / 7))}s`
          };
        }
        return item;
      }));
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const handleAnalyze = () => {
    if (!urlInput.trim()) return;
    setIsAnalyzing(true);
    setTimeout(() => {
      const mockInfo: VideoMetadata = {
        id: 'yt_' + Math.random().toString(36).substring(7),
        url: urlInput,
        title: urlInput.includes('youtu') ? 'فيديو يوتيوب مميز: شرح شامل للمشروع والمميزات والتقنيات الحديثة' : 'فيديو وسائط متكامل - تحليل فائق السرعة عبر yt-dlp',
        author: 'قناة البرمجة والتقنية الحديثة',
        authorAvatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80',
        duration: '14:32',
        durationSeconds: 872,
        thumbnail: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&auto=format&fit=crop&q=80',
        views: '1.4M',
        uploadedAt: 'منذ 3 أيام',
        description: 'شرح متكامل لآلية تحميل وتقطيع الفيديوهات بدقة عالية عبر محرك yt-dlp ومكتبة FFmpeg مع الحفاظ على أعلى جودة صوت وصورة.'
      };
      setVideoInfo(mockInfo);
      setSelectedFormat(sampleFormats[0]);
      setIsAnalyzing(false);
    }, 1200);
  };

  const handleStartDownload = () => {
    if (!videoInfo || !selectedFormat) return;
    const estMb = parseInt(selectedFormat.sizeEstimate) || 50;
    const totalBytes = estMb * 1024 * 1024;
    const newDownload: DownloadItem = {
      id: 'dl-' + Date.now(),
      url: videoInfo.url,
      title: enableCut ? `${videoInfo.title} [مقطع ${startTime}-${endTime}]` : videoInfo.title,
      thumbnail: videoInfo.thumbnail,
      format: selectedFormat,
      progress: 5,
      downloadedBytes: Math.floor(totalBytes * 0.05),
      totalBytes: totalBytes,
      speed: '5.2 MB/s',
      eta: '12s',
      status: 'downloading',
      timeRange: enableCut ? { start: startTime, end: endTime } : undefined,
      createdAt: Date.now(),
      localPath: `/storage/emulated/0/Download/DownloadVideos/${videoInfo.id}.${selectedFormat.ext.toLowerCase()}`
    };
    setDownloads([newDownload, ...downloads]);
    setActiveTab('downloads');
  };

  const isRtl = lang === 'ar';

  return (
    <div className={`min-h-screen bg-[#0f0f0f] text-[#f1f1f1] flex flex-col ${isRtl ? 'rtl font-cairo' : 'ltr font-sans'}`} dir={isRtl ? 'rtl' : 'ltr'}>
      {/* Top App Bar */}
      <header className="sticky top-0 z-50 bg-[#121212]/95 backdrop-blur border-b border-[#272727] px-4 py-3 flex items-center justify-between shadow-md">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-red-600 flex items-center justify-center shadow-lg shadow-red-600/30">
            <Download className="w-6 h-6 text-white animate-pulse" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-lg leading-tight text-white">
                {lang === 'ar' ? 'تحميل الفيديوهات' : 'Download Videos'}
              </h1>
              <span className="text-[10px] uppercase font-bold tracking-wider px-1.5 py-0.5 rounded bg-red-600/20 text-red-400 border border-red-500/30">
                yt-dlp v2026.08
              </span>
            </div>
            <p className="text-xs text-neutral-400">
              {lang === 'ar' ? 'محمل يوتيوب والوسائط فائق السرعة عبر FFmpeg' : 'High-speed YouTube & Media downloader with FFmpeg'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Language Switch */}
          <button
            onClick={() => setLang(l => l === 'ar' ? 'en' : 'ar')}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#222] hover:bg-[#333] border border-[#333] text-xs font-medium transition"
          >
            <Globe className="w-4 h-4 text-red-400" />
            <span>{lang === 'ar' ? 'English' : 'عربي'}</span>
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 max-w-5xl w-full mx-auto p-4 sm:p-6 pb-24">
        {/* Navigation Tabs */}
        <div className="grid grid-cols-4 gap-2 p-1.5 bg-[#181818] rounded-2xl border border-[#282828] mb-6 shadow-inner">
          <button
            onClick={() => setActiveTab('home')}
            className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold transition ${
              activeTab === 'home'
                ? 'bg-red-600 text-white shadow-md shadow-red-600/20'
                : 'text-neutral-400 hover:text-white hover:bg-[#242424]'
            }`}
          >
            <Search className="w-4 h-4" />
            <span className="hidden sm:inline">{lang === 'ar' ? 'الرئيسية والتحليل' : 'Analyze & URL'}</span>
          </button>
          <button
            onClick={() => setActiveTab('downloads')}
            className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold transition relative ${
              activeTab === 'downloads'
                ? 'bg-red-600 text-white shadow-md shadow-red-600/20'
                : 'text-neutral-400 hover:text-white hover:bg-[#242424]'
            }`}
          >
            <Download className="w-4 h-4" />
            <span className="hidden sm:inline">{lang === 'ar' ? 'التنزيلات' : 'Downloads'}</span>
            {downloads.filter(d => d.status === 'downloading').length > 0 && (
              <span className="w-2 h-2 rounded-full bg-red-400 animate-ping absolute top-2 end-2" />
            )}
          </button>
          <button
            onClick={() => setActiveTab('player')}
            className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold transition ${
              activeTab === 'player'
                ? 'bg-red-600 text-white shadow-md shadow-red-600/20'
                : 'text-neutral-400 hover:text-white hover:bg-[#242424]'
            }`}
          >
            <Play className="w-4 h-4" />
            <span className="hidden sm:inline">{lang === 'ar' ? 'المشغل الداخلي' : 'Media Player'}</span>
          </button>
          <button
            onClick={() => setActiveTab('settings')}
            className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold transition ${
              activeTab === 'settings'
                ? 'bg-red-600 text-white shadow-md shadow-red-600/20'
                : 'text-neutral-400 hover:text-white hover:bg-[#242424]'
            }`}
          >
            <Settings className="w-4 h-4" />
            <span className="hidden sm:inline">{lang === 'ar' ? 'الإعدادات' : 'Settings'}</span>
          </button>
        </div>

        {/* TAB 1: HOME & ANALYZER */}
        {activeTab === 'home' && (
          <div className="space-y-6">
            {/* URL Input Box */}
            <div className="bg-[#181818] border border-[#2a2a2a] rounded-3xl p-5 sm:p-6 shadow-xl relative overflow-hidden">
              <div className="absolute top-0 end-0 w-64 h-64 bg-red-600/5 rounded-full blur-3xl pointer-events-none" />

              <h2 className="text-lg font-bold text-white mb-3 flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-red-500" />
                {lang === 'ar' ? 'أدخل رابط الفيديو للتحميل والتقطيع' : 'Paste Video URL to Analyze & Download'}
              </h2>

              <div className="flex flex-col sm:flex-row gap-3">
                <div className="relative flex-1">
                  <input
                    type="url"
                    value={urlInput}
                    onChange={(e) => setUrlInput(e.target.value)}
                    placeholder={lang === 'ar' ? 'الصق رابط يوتيوب هنا https://www.youtube.com/watch?v=...' : 'Paste YouTube URL here https://www.youtube.com/watch?v=...'}
                    className="w-full bg-[#0d0d0d] border border-[#333] focus:border-red-500 focus:ring-1 focus:ring-red-500 rounded-2xl px-4 py-3.5 text-sm text-white placeholder-neutral-500 outline-none transition"
                  />
                  {urlInput && (
                    <button
                      onClick={() => setUrlInput('')}
                      className="absolute end-3 top-3.5 text-neutral-400 hover:text-white"
                    >
                      ✕
                    </button>
                  )}
                </div>

                <div className="flex gap-2">
                  <button
                    onClick={() => setUrlInput('https://www.youtube.com/watch?v=dQw4w9WgXcQ')}
                    className="px-4 py-3 bg-[#242424] hover:bg-[#2d2d2d] border border-[#383838] text-neutral-200 text-xs font-semibold rounded-2xl flex items-center gap-2 transition"
                  >
                    <Copy className="w-4 h-4 text-red-400" />
                    <span>{lang === 'ar' ? 'رابط تجريبي' : 'Sample'}</span>
                  </button>

                  <button
                    onClick={handleAnalyze}
                    disabled={isAnalyzing || !urlInput.trim()}
                    className="px-6 py-3 bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:hover:bg-red-600 text-white text-sm font-bold rounded-2xl flex items-center gap-2 shadow-lg shadow-red-600/30 transition cursor-pointer"
                  >
                    {isAnalyzing ? (
                      <>
                        <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                        <span>{lang === 'ar' ? 'جارِ التحليل...' : 'Analyzing...'}</span>
                      </>
                    ) : (
                      <>
                        <Search className="w-4 h-4" />
                        <span>{lang === 'ar' ? 'تحليل الرابط' : 'Analyze'}</span>
                      </>
                    )}
                  </button>
                </div>
              </div>

              {/* Supported Platforms tags */}
              <div className="mt-4 pt-4 border-t border-[#252525] flex flex-wrap items-center gap-2 text-xs text-neutral-400">
                <span className="font-medium">{lang === 'ar' ? 'المنصات المدعومة:' : 'Supported:'}</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">YouTube</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">YouTube Shorts</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">SoundCloud</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">TikTok</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">Vimeo</span>
                <span className="px-2.5 py-1 bg-[#222] rounded-lg text-neutral-300">Twitter / X</span>
              </div>
            </div>

            {/* Video Analysis Result */}
            {videoInfo && (
              <div className="bg-[#181818] border border-[#2b2b2b] rounded-3xl p-5 sm:p-6 shadow-2xl space-y-6 animate-fadeIn">
                {/* Header Video Info Card */}
                <div className="flex flex-col md:flex-row gap-5 items-start">
                  <div className="relative w-full md:w-72 aspect-video rounded-2xl overflow-hidden bg-black shrink-0 border border-[#333] shadow-lg group">
                    <img
                      src={videoInfo.thumbnail}
                      alt={videoInfo.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition duration-300"
                    />
                    <span className="absolute bottom-2 end-2 bg-black/80 backdrop-blur px-2 py-0.5 rounded-md text-xs font-bold text-white border border-white/10">
                      {videoInfo.duration}
                    </span>
                  </div>

                  <div className="flex-1 space-y-2.5">
                    <div className="flex items-center gap-2 text-xs text-neutral-400">
                      <span className="flex items-center gap-1">
                        <User className="w-3.5 h-3.5 text-red-400" />
                        {videoInfo.author}
                      </span>
                      <span>•</span>
                      <span className="flex items-center gap-1">
                        <Eye className="w-3.5 h-3.5" />
                        {videoInfo.views}
                      </span>
                      <span>•</span>
                      <span className="flex items-center gap-1">
                        <Calendar className="w-3.5 h-3.5" />
                        {videoInfo.uploadedAt}
                      </span>
                    </div>

                    <h3 className="text-lg font-bold text-white leading-snug">
                      {videoInfo.title}
                    </h3>

                    <p className="text-xs text-neutral-400 line-clamp-2 leading-relaxed">
                      {videoInfo.description}
                    </p>
                  </div>
                </div>

                {/* Trimming & Cut Mode Option */}
                <div className="p-4 bg-[#1f1f1f] rounded-2xl border border-[#333] space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Scissors className="w-5 h-5 text-red-500" />
                      <div>
                        <span className="text-sm font-bold text-white">
                          {lang === 'ar' ? 'قص وتقطيع الفيديو (FFmpeg Cut Mode)' : 'Cut & Trim Video (FFmpeg)'}
                        </span>
                        <p className="text-xs text-neutral-400">
                          {lang === 'ar' ? 'تحديد فترة زمنية معينة وتنزيل الجزء المطلوب فقط بدقة' : 'Download only a specific clip/segment'}
                        </p>
                      </div>
                    </div>

                    <label className="relative inline-flex items-center cursor-pointer">
                      <input
                        type="checkbox"
                        checked={enableCut}
                        onChange={(e) => setEnableCut(e.target.checked)}
                        className="sr-only peer"
                      />
                      <div className="w-11 h-6 bg-[#333] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-red-600"></div>
                    </label>
                  </div>

                  {enableCut && (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2 border-t border-[#2e2e2e]">
                      <div>
                        <label className="text-xs font-semibold text-neutral-300 block mb-1">
                          {lang === 'ar' ? 'وقت البدء (ساعة:دقيقة:ثانية)' : 'Start Time (hh:mm:ss)'}
                        </label>
                        <input
                          type="text"
                          value={startTime}
                          onChange={(e) => setStartTime(e.target.value)}
                          className="w-full bg-[#141414] border border-[#3d3d3d] rounded-xl px-3 py-2 text-sm text-white font-mono text-center outline-none focus:border-red-500"
                        />
                      </div>
                      <div>
                        <label className="text-xs font-semibold text-neutral-300 block mb-1">
                          {lang === 'ar' ? 'وقت الانتهاء (ساعة:دقيقة:ثانية)' : 'End Time (hh:mm:ss)'}
                        </label>
                        <input
                          type="text"
                          value={endTime}
                          onChange={(e) => setEndTime(e.target.value)}
                          className="w-full bg-[#141414] border border-[#3d3d3d] rounded-xl px-3 py-2 text-sm text-white font-mono text-center outline-none focus:border-red-500"
                        />
                      </div>
                    </div>
                  )}
                </div>

                {/* Available Formats & Qualities */}
                <div className="space-y-3">
                  <h4 className="text-sm font-bold text-white flex items-center gap-2">
                    <Layers className="w-4 h-4 text-red-400" />
                    {lang === 'ar' ? 'اختر الصيغة والجودة المطلوبة' : 'Select Quality & Format'}
                  </h4>

                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                    {sampleFormats.map((fmt) => {
                      const isSelected = selectedFormat?.id === fmt.id;
                      return (
                        <div
                          key={fmt.id}
                          onClick={() => setSelectedFormat(fmt)}
                          className={`p-3.5 rounded-2xl border transition cursor-pointer flex items-center justify-between ${
                            isSelected
                              ? 'bg-red-600/10 border-red-500 ring-1 ring-red-500'
                              : 'bg-[#1e1e1e] border-[#2e2e2e] hover:border-[#444] hover:bg-[#252525]'
                          }`}
                        >
                          <div className="flex items-center gap-3">
                            <div className={`p-2 rounded-xl ${fmt.type === 'video' ? 'bg-blue-500/20 text-blue-400' : 'bg-purple-500/20 text-purple-400'}`}>
                              {fmt.type === 'video' ? <Film className="w-4 h-4" /> : <Music className="w-4 h-4" />}
                            </div>
                            <div>
                              <div className="text-sm font-bold text-white flex items-center gap-1.5">
                                <span>{fmt.label}</span>
                              </div>
                              <span className="text-xs text-neutral-400">
                                {fmt.ext} • {fmt.sizeEstimate}
                              </span>
                            </div>
                          </div>

                          <div className="w-5 h-5 rounded-full border border-neutral-500 flex items-center justify-center">
                            {isSelected && <div className="w-3 h-3 rounded-full bg-red-500" />}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Action Trigger */}
                <div className="pt-2 flex justify-end">
                  <button
                    onClick={handleStartDownload}
                    className="w-full sm:w-auto px-8 py-3.5 bg-red-600 hover:bg-red-700 text-white text-base font-bold rounded-2xl flex items-center justify-center gap-3 shadow-xl shadow-red-600/30 transition transform active:scale-95"
                  >
                    <Download className="w-5 h-5" />
                    <span>
                      {lang === 'ar'
                        ? `بدء التنزيل الآن (${selectedFormat?.label || ''})`
                        : `Start Download (${selectedFormat?.label || ''})`}
                    </span>
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* TAB 2: DOWNLOADS QUEUE */}
        {activeTab === 'downloads' && (
          <div className="space-y-5">
            {/* Header & Filter Controls */}
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 bg-[#181818] p-4 rounded-2xl border border-[#2b2b2b]">
              <div>
                <h2 className="text-lg font-bold text-white flex items-center gap-2">
                  <Download className="w-5 h-5 text-red-500" />
                  {lang === 'ar' ? 'قائمة التنزيلات والمهام' : 'Downloads & Task Queue'}
                </h2>
                <p className="text-xs text-neutral-400">
                  {lang === 'ar' ? `إجمالي الملفات: ${downloads.length}` : `Total items: ${downloads.length}`}
                </p>
              </div>

              {/* Status Filter */}
              <div className="flex items-center gap-1.5 bg-[#101010] p-1 rounded-xl border border-[#2e2e2e] text-xs">
                {(['all', 'downloading', 'completed', 'failed'] as const).map((st) => (
                  <button
                    key={st}
                    onClick={() => setFilterStatus(st)}
                    className={`px-3 py-1.5 rounded-lg capitalize font-medium transition ${
                      filterStatus === st ? 'bg-red-600 text-white' : 'text-neutral-400 hover:text-white'
                    }`}
                  >
                    {st === 'all' && (lang === 'ar' ? 'الكل' : 'All')}
                    {st === 'downloading' && (lang === 'ar' ? 'قيد التنزيل' : 'Active')}
                    {st === 'completed' && (lang === 'ar' ? 'المكتملة' : 'Done')}
                    {st === 'failed' && (lang === 'ar' ? 'فشل' : 'Failed')}
                  </button>
                ))}
              </div>
            </div>

            {/* List of Downloads */}
            <div className="space-y-3">
              {downloads
                .filter(d => filterStatus === 'all' || d.status === filterStatus)
                .map((item) => (
                  <div
                    key={item.id}
                    className="bg-[#181818] border border-[#282828] rounded-2xl p-4 sm:p-5 shadow-lg space-y-3 hover:border-[#383838] transition"
                  >
                    <div className="flex flex-col sm:flex-row gap-4 items-start">
                      <img
                        src={item.thumbnail}
                        alt={item.title}
                        className="w-full sm:w-36 aspect-video object-cover rounded-xl border border-[#333] shrink-0"
                      />

                      <div className="flex-1 space-y-1.5 w-full">
                        <div className="flex items-start justify-between gap-2">
                          <h3 className="text-sm sm:text-base font-bold text-white line-clamp-2">
                            {item.title}
                          </h3>

                          {/* Status Badge */}
                          <span
                            className={`px-2.5 py-0.5 rounded-md text-xs font-bold shrink-0 ${
                              item.status === 'completed'
                                ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                                : item.status === 'downloading'
                                ? 'bg-red-500/20 text-red-400 border border-red-500/30 animate-pulse'
                                : item.status === 'paused'
                                ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                                : 'bg-red-900/40 text-red-300 border border-red-700'
                            }`}
                          >
                            {item.status === 'completed' && (lang === 'ar' ? 'مكتمل' : 'Completed')}
                            {item.status === 'downloading' && (lang === 'ar' ? 'جارِ التحميل' : 'Downloading')}
                            {item.status === 'paused' && (lang === 'ar' ? 'مؤقت' : 'Paused')}
                            {item.status === 'failed' && (lang === 'ar' ? 'فشل' : 'Failed')}
                          </span>
                        </div>

                        <div className="flex flex-wrap items-center gap-3 text-xs text-neutral-400">
                          <span className="font-semibold text-neutral-300">
                            {item.format.label} ({item.format.ext})
                          </span>
                          <span>•</span>
                          <span>{(item.downloadedBytes / (1024 * 1024)).toFixed(1)} MB / {(item.totalBytes / (1024 * 1024)).toFixed(1)} MB</span>
                          {item.status === 'downloading' && (
                            <>
                              <span>•</span>
                              <span className="text-red-400 font-mono">{item.speed}</span>
                              <span>•</span>
                              <span>{lang === 'ar' ? `المتبقي: ${item.eta}` : `ETA: ${item.eta}`}</span>
                            </>
                          )}
                        </div>

                        {/* Progress Bar */}
                        <div className="w-full bg-[#242424] h-2.5 rounded-full overflow-hidden border border-[#333]">
                          <div
                            className={`h-full transition-all duration-300 ${
                              item.status === 'completed'
                                ? 'bg-emerald-500'
                                : item.status === 'failed'
                                ? 'bg-red-600'
                                : 'bg-gradient-to-r from-red-600 to-red-400'
                            }`}
                            style={{ width: `${item.progress}%` }}
                          />
                        </div>
                      </div>
                    </div>

                    {/* Action buttons */}
                    <div className="flex items-center justify-between pt-2 border-t border-[#252525] text-xs">
                      <span className="text-neutral-500 truncate max-w-xs font-mono text-[11px]">
                        {item.localPath}
                      </span>

                      <div className="flex items-center gap-2">
                        {item.status === 'completed' && (
                          <button
                            onClick={() => {
                              setActivePlayingItem(item);
                              setActiveTab('player');
                            }}
                            className="px-3 py-1.5 bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-300 border border-emerald-500/30 rounded-xl flex items-center gap-1.5 font-semibold transition"
                          >
                            <Play className="w-3.5 h-3.5 fill-emerald-300" />
                            <span>{lang === 'ar' ? 'تشغيل' : 'Play'}</span>
                          </button>
                        )}

                        {item.status === 'downloading' && (
                          <button
                            onClick={() => {
                              setDownloads(prev => prev.map(d => d.id === item.id ? { ...d, status: 'paused' } : d));
                            }}
                            className="p-1.5 bg-[#2a2a2a] hover:bg-[#353535] rounded-xl text-neutral-300 transition"
                            title={lang === 'ar' ? 'إيقاف مؤقت' : 'Pause'}
                          >
                            <Pause className="w-4 h-4" />
                          </button>
                        )}

                        {item.status === 'paused' && (
                          <button
                            onClick={() => {
                              setDownloads(prev => prev.map(d => d.id === item.id ? { ...d, status: 'downloading' } : d));
                            }}
                            className="p-1.5 bg-red-600 hover:bg-red-700 rounded-xl text-white transition"
                            title={lang === 'ar' ? 'استئناف' : 'Resume'}
                          >
                            <Play className="w-4 h-4" />
                          </button>
                        )}

                        <button
                          onClick={() => {
                            setDownloads(prev => prev.filter(d => d.id !== item.id));
                          }}
                          className="p-1.5 bg-[#2a2a2a] hover:bg-red-900/30 hover:text-red-400 rounded-xl text-neutral-400 transition"
                          title={lang === 'ar' ? 'حذف' : 'Delete'}
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                ))}

              {downloads.length === 0 && (
                <div className="text-center py-16 bg-[#181818] rounded-3xl border border-[#252525]">
                  <Download className="w-12 h-12 text-neutral-600 mx-auto mb-3" />
                  <h3 className="text-base font-bold text-neutral-300">
                    {lang === 'ar' ? 'لا توجد تنزيلات حالية' : 'No downloads yet'}
                  </h3>
                  <p className="text-xs text-neutral-500 mt-1">
                    {lang === 'ar' ? 'قم بإدخال رابط في تبويب التحليل للبدء' : 'Enter a video link in the Analyze tab to get started'}
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* TAB 3: MEDIA PLAYER */}
        {activeTab === 'player' && (
          <div className="space-y-6">
            <div className="bg-[#181818] border border-[#2a2a2a] rounded-3xl p-5 sm:p-6 shadow-xl space-y-4">
              <h2 className="text-lg font-bold text-white flex items-center gap-2">
                <Play className="w-5 h-5 text-red-500 fill-red-500" />
                {lang === 'ar' ? 'المشغل الداخلي للفيديوهات والصوتيات' : 'In-App Media Player'}
              </h2>

              {/* Player Viewport */}
              <div className="relative aspect-video w-full max-w-3xl mx-auto rounded-2xl overflow-hidden bg-black border border-[#333] shadow-2xl flex items-center justify-center">
                <img
                  src={activePlayingItem?.thumbnail || 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=1200&auto=format&fit=crop&q=80'}
                  alt="Video playing"
                  className="w-full h-full object-cover opacity-85"
                />

                {/* Big Center Play/Pause button */}
                <button
                  onClick={() => setIsPlaying(!isPlaying)}
                  className="absolute p-5 rounded-full bg-red-600/90 hover:bg-red-600 text-white shadow-2xl backdrop-blur transition transform hover:scale-110"
                >
                  {isPlaying ? <Pause className="w-8 h-8" /> : <Play className="w-8 h-8 fill-white" />}
                </button>

                {/* Overlay Controls */}
                <div className="absolute inset-x-0 bottom-0 p-4 bg-gradient-to-t from-black/90 via-black/50 to-transparent space-y-2">
                  <div className="w-full bg-neutral-700 h-1.5 rounded-full overflow-hidden cursor-pointer">
                    <div className="bg-red-600 h-full w-2/5" />
                  </div>

                  <div className="flex items-center justify-between text-xs text-white">
                    <div className="flex items-center gap-3">
                      <button onClick={() => setIsPlaying(!isPlaying)}>
                        {isPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                      </button>
                      <Volume2 className="w-4 h-4 text-neutral-300" />
                      <span className="font-mono">03:45 / 14:32</span>
                    </div>

                    <div className="flex items-center gap-2">
                      <span className="bg-red-600 px-2 py-0.5 rounded text-[10px] font-bold">1080p 60fps</span>
                      <Maximize2 className="w-4 h-4 text-neutral-300 cursor-pointer" />
                    </div>
                  </div>
                </div>
              </div>

              {/* Title & Metadata */}
              <div className="p-4 bg-[#202020] rounded-2xl border border-[#333]">
                <h3 className="text-base font-bold text-white">
                  {activePlayingItem?.title || 'تعلم تطوير تطبيقات أندرويد و Kotlin بأسلوب حديث 2026'}
                </h3>
                <p className="text-xs text-neutral-400 mt-1 font-mono">
                  {activePlayingItem?.localPath || '/storage/emulated/0/Download/DownloadVideos/android_tutorial.mp4'}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* TAB 4: SETTINGS */}
        {activeTab === 'settings' && (
          <div className="space-y-6">
            {/* System Engines Card */}
            <div className="bg-[#181818] border border-[#2a2a2a] rounded-3xl p-5 sm:p-6 shadow-xl space-y-4">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <Cpu className="w-5 h-5 text-red-500" />
                {lang === 'ar' ? 'المحركات وبيئة التشغيل (Engines & Runtime)' : 'Engines & Runtime'}
              </h2>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <div className="p-4 bg-[#202020] border border-[#333] rounded-2xl space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-neutral-300">yt-dlp Engine</span>
                    <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                  </div>
                  <div className="text-sm font-bold text-white">v2026.08.14</div>
                  <p className="text-[11px] text-neutral-400">Stable release with YouTube JS cipher support</p>
                </div>

                <div className="p-4 bg-[#202020] border border-[#333] rounded-2xl space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-neutral-300">FFmpeg Binary</span>
                    <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                  </div>
                  <div className="text-sm font-bold text-white">v7.0.2 (GPL)</div>
                  <p className="text-[11px] text-neutral-400">Audio muxing, fast cutting & video transcode</p>
                </div>

                <div className="p-4 bg-[#202020] border border-[#333] rounded-2xl space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-neutral-300">Embedded Python</span>
                    <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                  </div>
                  <div className="text-sm font-bold text-white">Python 3.11.8</div>
                  <p className="text-[11px] text-neutral-400">Chaquopy / NDK native runtime initialized</p>
                </div>
              </div>
            </div>

            {/* Storage & Queue Settings */}
            <div className="bg-[#181818] border border-[#2a2a2a] rounded-3xl p-5 sm:p-6 shadow-xl space-y-4">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <HardDrive className="w-5 h-5 text-red-500" />
                {lang === 'ar' ? 'التخزين وقائمة الانتظار (Storage & Queue)' : 'Storage & Queue'}
              </h2>

              <div className="space-y-4">
                <div className="flex items-center justify-between p-3 bg-[#202020] rounded-2xl border border-[#333]">
                  <div>
                    <div className="text-sm font-bold text-white">
                      {lang === 'ar' ? 'مسار الحفظ الافتراضي' : 'Default Save Directory'}
                    </div>
                    <div className="text-xs text-neutral-400 font-mono mt-0.5">
                      /storage/emulated/0/Download/DownloadVideos
                    </div>
                  </div>
                  <FolderDown className="w-5 h-5 text-red-400" />
                </div>

                <div className="flex items-center justify-between p-3 bg-[#202020] rounded-2xl border border-[#333]">
                  <div>
                    <div className="text-sm font-bold text-white">
                      {lang === 'ar' ? 'أقصى عدد للتحميلات المتزامنة' : 'Max Concurrent Downloads'}
                    </div>
                    <div className="text-xs text-neutral-400">
                      {lang === 'ar' ? 'التحكم في عدد المهام التي تعمل معاً في نفس الوقت' : 'Simultaneous active download tasks'}
                    </div>
                  </div>
                  <select
                    value={maxConcurrent}
                    onChange={(e) => setMaxConcurrent(Number(e.target.value))}
                    className="bg-[#141414] border border-[#444] text-white text-sm font-bold rounded-xl px-3 py-1.5 outline-none"
                  >
                    <option value={1}>1</option>
                    <option value={2}>2</option>
                    <option value={3}>3</option>
                    <option value={5}>5</option>
                  </select>
                </div>

                <div className="flex items-center justify-between p-3 bg-[#202020] rounded-2xl border border-[#333]">
                  <div>
                    <div className="text-sm font-bold text-white">
                      {lang === 'ar' ? 'إعادة المحاولة التلقائية عند انقطاع الاتصال' : 'Auto-Retry Failed Downloads'}
                    </div>
                    <div className="text-xs text-neutral-400">
                      {lang === 'ar' ? 'إعادة المحاولة حتى 3 مرات مع استئناف التحميل' : 'Retry up to 3 times on transient errors'}
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={autoRetry}
                    onChange={(e) => setAutoRetry(e.target.checked)}
                    className="w-5 h-5 accent-red-600 rounded cursor-pointer"
                  />
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Persistent Bottom Bar / Indicator */}
      <footer className="fixed bottom-0 inset-x-0 bg-[#121212]/95 backdrop-blur border-t border-[#262626] px-4 py-2 text-center text-xs text-neutral-400 flex items-center justify-between">
        <span className="flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
          {lang === 'ar' ? 'المحرك جاهز للتحميل والتقطيع' : 'Engine Ready (yt-dlp + FFmpeg)'}
        </span>
        <span className="text-neutral-500 font-mono text-[11px]">
          v1.0.0 • Kotlin / Jetpack Compose & Web
        </span>
      </footer>
    </div>
  );
}
