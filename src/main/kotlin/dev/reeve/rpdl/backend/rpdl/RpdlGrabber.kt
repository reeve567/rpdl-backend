package dev.reeve.rpdl.backend.rpdl

import dev.reeve.rpdl.backend.Caches
import dev.reeve.rpdl.backend.Settings
import com.google.gson.Gson
import dev.reeve.torrustapi.Torrust
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.collections.HashSet
import kotlin.system.measureTimeMillis

class RpdlGrabber {
	private val torrust = Torrust(Settings.Url.rpdlURL, true)
	private val badList = HashSet<Int>()
	
	private fun getCategories(): List<Category> {
		return torrust.getCategories()?.map {
			val search = torrust.getListings(categories = arrayOf(it.name), limit = 1)!!
			Category(search.results.first().categoryId, it.name)
		} ?: emptyList()
	}
	
	fun checkForUpdates() {
		println("Checking for updates...")
		
		if (Settings.databaseManager.getCategories().isEmpty()) {
			// initialize categories
			Settings.rpdl.getCategories().forEach {
				Settings.databaseManager.putCategory(it)
			}
		}
		
		val updateSet = HashSet<Int>()
		
		val uploaders = Caches.uploaderCache.getAndSet().associateBy {
			it.name
		}.toMutableMap()
		Caches.categoryCache.getAndSet()
		
		/*val lastCheck = Settings.databaseManager.getLastCheck()*/
		val lastCheck = Date(0)
		
		println("Last check was $lastCheck")
		
		val updates = if (lastCheck.time != 0L) torrust.getNewWebListings(lastCheck)
		else torrust.getNewWebListings(null)
		val instances = Caches.instanceCache.getAndSet()
		
		val start = Date()
		
		if (updates != null && updates.total > 0) {
			println("Found ${updates.total} updates")
			
			val found = HashMap<Int, HashSet<Long>>()
			
			runBlocking {
				var done = 0
				
				updates@for (i in 1..updates.total) {
					val webListing = updates.results[updates.total - i]
					
					val descriptionInfo = getDataFromDescription(webListing.description, webListing.torrentId) ?: continue@updates
					
					val games = instances.filter {
						it.value.threadId == descriptionInfo.first || it.value.torrentId == webListing.torrentId
					}
					
					val copy = games.filter {
						it.value.torrentId == webListing.torrentId && it.value.threadId == descriptionInfo.first
					}
					
					if (copy.isNotEmpty()) {
						continue@updates
					}
					
					if (games.size > 1) {
						println("Multiple games found for ${descriptionInfo.first}, looking...")
					}
					val result: MutableList<GameInstance>
					
					val time = measureTimeMillis {
						result = games.filter { (id, gameInstance) ->
							if (found.containsKey(descriptionInfo.first) && found[descriptionInfo.first]!!.contains(gameInstance.torrentId.toLong())) {
								return@filter false
							}
							
							val res = torrust.getWebListing(gameInstance.torrentId.toLong())
							
							if (res != null) {
								found.getOrPut(descriptionInfo.first) { HashSet() }.add(res.torrentId)
							}
							
							return@filter res == null
						}.map { it.value }.toMutableList()
					}
					
					if (games.size > 1) {
						println("Done looking for ${descriptionInfo.first}, took $time")
					}
					
					var title = webListing.title
					
					val version = if (title.startsWith("LS-")) {
						""
					} else if (title.indexOf("-") != -1) {
						title.substringAfter("-")
					} else {
						""
					}
					
					title = title.replace(version, "")
					if (version != "") title = title.substringBeforeLast("-")
					
					val uploader = if (uploaders.containsKey(webListing.uploader)) {
						uploaders[webListing.uploader]!!
					} else {
						Uploader(null, webListing.uploader, webListing.uploadDate)
					}
					
					if (webListing.uploadDate > uploader.lastSeen) {
						uploader.lastSeen = webListing.uploadDate
						Caches.uploaderCache.get().find { it.name == uploader.name }?.lastSeen = uploader.lastSeen
					}
					
					if (uploader.id == null) {
						uploaders[webListing.uploader] = uploader
						Settings.databaseManager.putUploader(uploader)
						Caches.uploaderCache.get().add(uploader)
					}
					
					if (result.size > 1) {
						println("Multiple games found for ${descriptionInfo.first}, looking for issues...")
						
						if (result.all {
							return@all torrust.getWebListing(it.torrentId) == null
						}) {
							println("All games are missing, removing all...")
							result.forEach {
								Settings.databaseManager.removeGameInstance(it)
								Caches.instanceCache.get().remove(it.id)
							}
							result.clear()
						}
					}
					
					val instance = if (result.size > 1) {
						println("Error updating ${webListing.toString().replace('\n', ' ')}")
						println("Multiple possible options: ${result.joinToString("\n", prefix = "\n", postfix = "\n------------------------") { it.toString().replace('\n', ' ')}}")
						
						null
					} else if (result.size == 1) {
						GameInstance(
							result.first().id, // xwy id
							descriptionInfo.first, // threadID
							title,
							version,
							webListing.fileSize.toLong(),
							Caches.categoryCache.get()[webListing.categoryId]!!,
							webListing.torrentId,
							webListing.uploadDate,
							uploader,
							descriptionInfo.second // links
						)
					} else {
						GameInstance(
							null, // don't have an ID assigned yet
							descriptionInfo.first, // threadID
							title,
							version,
							webListing.fileSize.toLong(),
							Caches.categoryCache.get()[webListing.categoryId]!!,
							webListing.torrentId,
							webListing.uploadDate,
							uploader,
							descriptionInfo.second // links
						)
					}
					
					if (instance != null) {
						val ret = Settings.f95.downloadPage(descriptionInfo.first, updateSet)
						
						if (ret == null) {
							if (!badList.contains(instance.threadId!!)) {
								println("Dead link = https://dl.rpdl.net/torrent/${instance.torrentId} - ${instance.uploader.name} - (${instance.links?.get("f95zone")} vs ${Settings.Url.f95URL}threads/${instance.threadId})")
								badList.add(instance.threadId)
							}
							continue@updates
						} else {
							if (result.size == 1) {
								println("Update ${result.first().title} to $version")
							} else if (result.isEmpty()) {
								println("Add ${webListing.title} with torrentID ${webListing.torrentId}")
							}
						}
						
						Settings.databaseManager.putGameInstance(instance)
						
						instances[instance.id!!] = instance
					}
					
					if (++done % 50 == 0) {
						println("Done $done")
						println("Elapsed: ${(Date().time - start.time) / 1000} seconds")
						println("Remaining: ${((Date().time - start.time) / 1000) * (updates.total - done) / done} seconds")
						if (done % 100 == 0) {
							Settings.databaseManager.reindex()
						}
					}
				}
			}
			
			uploaders.forEach {
				Settings.databaseManager.putUploader(it.value)
			}
			
			Settings.databaseManager.putLastCheck(start)
		} else {
			println("No updates were found")
		}
		
		println("Done updating, used ${torrust.calls.values.sum()} calls")
		for (entry in torrust.calls.entries.sortedByDescending { it.value }) {
			println("${entry.key}: ${entry.value}")
		}
		torrust.calls.clear()
		
		Caches.uploaderCache.clear()
		Caches.categoryCache.clear()
		Caches.instanceCache.clear()
		Settings.databaseManager.reindex()
	}
	
	// Moved to client so that they don't have to send auth info
	/*
	fun getMagnetLink(torrentID: Long): String {
			val url = "${dev.reeve.rpdl.backend.api.Settings.Url.rpdlURL}torrent/$torrentID"
			
			val request = Request.Builder()
				.url(url)
				.build()
			
			client.newCall(request).execute().use {
				val response = it.body!!.string()
				
				val results = dev.reeve.rpdl.backend.api.Settings.RegExp.link.findAll(response)
				
				return results.last().groupValues[1]
			}
		}*/
	
	private fun getDataFromDescription(description: String, torrentID: Long): Pair<Int, HashMap<String, String>>? {
		val links = HashMap<String, String>()
		
		Settings.RegExp.markdown.findAll(description).forEach {
			links[it.groupValues[1].replace("[", "").lowercase()] = it.groupValues[2]
		}
		
		val f95Url = links.getOrDefault("f95zone", "")
		val threadID = Settings.RegExp.threadID.find(f95Url)?.groupValues?.get(0)?.toIntOrNull()
		
		if (threadID == null) {
			if (f95Url != "" && f95Url.indexOf("/search/") != -1) {
				println("Problem child, search result, ${Settings.Url.rpdlURL}torrent/$torrentID URL: $f95Url")
			} else {
				println("Problem child, couldn't find F95 Url, ${Settings.Url.rpdlURL}torrent/$torrentID")
				println("Links: ${Gson().toJson(links)}")
			}
			return null
		} else {
			if (f95Url.indexOf("f95zone.to") == -1) {
				println("Problem child, not f95zone.to, ${Settings.Url.rpdlURL}torrent/$torrentID URL: $f95Url")
				return null
			}
		}
		
		return threadID to links
	}
}