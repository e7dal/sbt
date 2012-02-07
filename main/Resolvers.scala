/* sbt -- Simple Build Tool
 * Copyright 2011 Sanjin Sehic
 */

package sbt

import java.io.File
import java.net.URI

import BuildLoader.ResolveInfo
import RichURI.fromURI

object Resolvers
{
	type Resolver = BuildLoader.Resolver

	val local: Resolver = (info: ResolveInfo) => {
		val uri = info.uri
		val from = new File(uri)
		val to = uniqueSubdirectoryFor(uri, in = info.staging)

		if (from.isDirectory) Some {() => creates(to) {IO.copyDirectory(from, to)}}
		else None
	}

	val remote: Resolver = (info: ResolveInfo) => {
		val url = info.uri.toURL
		val to = uniqueSubdirectoryFor(info.uri, in = info.staging)

		Some {() => creates(to) {IO.unzipURL(url, to)}}
	}

	val subversion: Resolver = (info: ResolveInfo) => {
		def normalized(uri: URI) = uri.copy(scheme = "svn")

		val uri = info.uri.withoutMarkerScheme
		val localCopy = uniqueSubdirectoryFor(normalized(uri), in = info.staging)
		val from = uri.withoutFragment.toASCIIString
		val to = localCopy.getAbsolutePath

		if (uri.hasFragment) {
			val revision = uri.getFragment
			Some {
				() => creates(localCopy) {
					run("svn", "checkout", "-q", "-r", revision, from, to) |>: stdout
				}
			}
		} else
			Some {
				() => creates(localCopy) {
					run("svn", "checkout", "-q", from, to) |>: stdout
				}
			}
	}

	val mercurial: Resolver = new DistributedVCS
	{
		override val scheme = "hg"

		override def clone(from: String, to: File) {
			run("hg", "clone", from, to.getAbsolutePath) |>: stdout
		}

		override def checkout(branch: String, in: File)
		{
			run(Some(in), "hg", "checkout", "-q", branch) |>: stdout
		}
	}.toResolver

	val git: Resolver = new DistributedVCS
	{
		override val scheme = "git"

		override def clone(from: String, to: File)
		{
			run("git", "clone", from, to.getAbsolutePath) |>: stdout
			checkout("origin/HEAD", to)
			// get names of all remote branches
			val branches = run(Some(to), "git", "ls-remote", "--heads", "origin") map {_.trim.substring(52)}
			branches foreach {
				branch =>
					// locally track the remote branch
					run(Some(to), "git", "branch", "--track", "--force", branch, "origin/" + branch)
			}
		}

		override def checkout(branch: String, in: File)
		{
			run(Some(in), "git", "checkout", "-q", branch) |>: stdout
		}
	}.toResolver

	abstract class DistributedVCS
	{
		val scheme: String

		def clone(from: String, to: File)

		def checkout(branch: String, in: File)

		def toResolver: Resolver = (info: ResolveInfo) => {
			val uri = info.uri.withoutMarkerScheme
			val staging = info.staging
			val localCopy = uniqueSubdirectoryFor(normalized(uri.withoutFragment), in = staging)
			val from = uri.withoutFragment.toASCIIString

			if (uri.hasFragment) {
				val branch = uri.getFragment
				val branchCopy = uniqueSubdirectoryFor(normalized(uri), in = staging)
				Some {
					() =>
						creates(localCopy) {clone(from, to = localCopy)}
						creates(branchCopy) {
							clone(localCopy.getAbsolutePath, to = branchCopy)
							checkout(branch, in = branchCopy)
						}
				}
			} else Some {() => creates(localCopy) {clone(from, to = localCopy)}}
		}

		private def normalized(uri: URI) = uri.copy(scheme = scheme)
	}

	private lazy val onWindows = {
		val os = System.getenv("OSTYPE")
		val isCygwin = (os != null) && os.toLowerCase.contains("cygwin")
		val isWindows = System.getProperty("os.name", "").toLowerCase.contains("windows")
		isWindows && !isCygwin
	}

	def run(command: String*): Stream[String] = run(None, command: _*)

	def run(cwd: Option[File], command: String*) = Process(
		if (onWindows) "cmd" +: "/c" +: command
		else command,
		cwd
	).lines

	def creates(file: File)(f: => Unit) =
	{
		if (!file.exists)
			try {
				f
			} catch {
				case e =>
					IO.delete(file)
					throw e
			}
		file
	}

	def uniqueSubdirectoryFor(uri: URI, in: File) = new File(in, Hash.halfHashString(uri.toASCIIString))

	object stdout {
		def |>:(seq: Seq[_]) {seq foreach (println _)}
	}
}
