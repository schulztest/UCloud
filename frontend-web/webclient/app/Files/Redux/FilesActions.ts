import { Cloud } from "Authentication/SDUCloudObject";
import {
    RECEIVE_FILES,
    UPDATE_FILES,
    SET_FILES_LOADING,
    UPDATE_PATH,
    SET_FILES_SORTING_COLUMN,
    FILE_SELECTOR_SHOWN,
    SET_FILE_SELECTOR_LOADING,
    RECEIVE_FILE_SELECTOR_FILES,
    SET_FILE_SELECTOR_CALLBACK,
    SET_DISALLOWED_PATHS,
    FILES_ERROR,
    SET_FILE_SELECTOR_ERROR,
    CHECK_ALL_FILES,
    CHECK_FILE,
    CREATE_FOLDER,
    FILES_INVALID_PATH
} from "./FilesReducer";
import { getFilenameFromPath, replaceHomeFolder, getParentPath, resolvePath, favoritesQuery, markFileAsChecked } from "Utilities/FileUtilities";
import { Page, ReceivePage, SetLoadingAction, Error, PayloadAction } from "Types";
import { SortOrder, SortBy, File, FileResource } from "..";
import { Action } from "redux";
import { filepathQuery, fileLookupQuery } from "Utilities/FileUtilities";
import { errorMessageOrDefault } from "UtilityFunctions";
import { emptyPage } from "DefaultObjects";

export type FileActions = Error<typeof FILES_ERROR> | ReceiveFiles | ReceivePage<typeof UPDATE_FILES, File> |
    SetLoadingAction<typeof SET_FILES_LOADING> | UpdatePathAction | FileSelectorShownAction |
    ReceiveFileSelectorFilesAction | Action<typeof SET_FILE_SELECTOR_LOADING> | SetFileSelectorCallbackAction |
    Error<typeof SET_FILE_SELECTOR_ERROR> | SetDisallowedPathsAction | SetSortingColumnAction | CheckAllFilesAction |
    CheckFileAction | CreateFolderAction | InvalidPathAction

/**
* Creates a promise to fetch files. Sorts the files based on sorting function passed,
* and implicitly sets {filesLoading} to false in the reducer when the files are fetched.
* @param {string} path is the path of the folder being queried
* @param {number} itemsPerPage number of items to be fetched
* @param {Page<File>} page number of the page to be fetched
*/

type FetchFiles = Promise<ReceivePage<typeof RECEIVE_FILES, File> | FilesError | InvalidPathAction>
export const fetchFiles = async (path: string, itemsPerPage: number, page: number, order: SortOrder, sortBy: SortBy, attrs: FileResource[]): FetchFiles => {
    try {
        const { response } = await Cloud.get<Page<File>>(filepathQuery(path, page, itemsPerPage, order, sortBy, attrs));
        return receiveFiles(response, path, order, sortBy)
    } catch (e) {
        const error = errorMessageOrDefault(e, "An error occurred fetching contents of folder.");
        if (e.request.status === 404 || e.request.status === 403) return setInvalidPath(error);
        return setErrorMessage(error);
    }
}
type FilesError = Error<typeof FILES_ERROR>
/**
 * Sets the error message for the Files component.
 * @param {error?} error the error message. undefined means nothing is rendered.
 */
export const setErrorMessage = (error?: string): Error<typeof FILES_ERROR> => ({
    type: FILES_ERROR,
    payload: { error }
});

/**
* Updates the files stored. 
* Intended for use when sorting the files, checking or favoriting, for instance.
* @param {Page<File>} page contains the currently held page with modifications made to the files client-side
*/
export const updateFiles = (page: Page<File>): ReceivePage<typeof UPDATE_FILES, File> => ({
    type: UPDATE_FILES,
    payload: { page }
});

/**
 * Sets whether or not the component is loading
 * @param {boolean} loading - whether or not it is loading
 */
export const setLoading = (loading: boolean): SetLoadingAction<typeof SET_FILES_LOADING> => ({
    type: SET_FILES_LOADING,
    payload: { loading }
});

interface UpdatePathAction extends Action<typeof UPDATE_PATH> { path: string }
/**
 * Updates the path currently held intended for the files/fileinfo components.
 * @param {string} path - The current path for the component
 */
export const updatePath = (path: string): UpdatePathAction => ({
    type: UPDATE_PATH,
    path: resolvePath(path)
});

interface ReceiveFiles extends PayloadAction<typeof RECEIVE_FILES, { path: string, sortOrder: SortOrder, sortBy: SortBy, page: Page<File> }> { }
/**
 * The function used for the actual receiving the files, rather than the promise
 * @param {Page<File>} page - Contains the page
 * @param {string} path - The path the files were retrieved from
 * @param {SortOrder} sortOrder - The order in which the files were sorted
 * @param {SortBy} sortBy - the value the sorting was based on
 */
export const receiveFiles = (page: Page<File>, path: string, sortOrder: SortOrder, sortBy: SortBy): ReceiveFiles => ({
    type: RECEIVE_FILES,
    payload: {
        page,
        path: resolvePath(path),
        sortOrder,
        sortBy
    }
});

export type SortingColumn = 0 | 1;
/**
 * Sets the column in the table that should be rendered (Not implemented)
 * @param index - the index of the sorting colum (0 or 1)
 * @param {SortOrder} asc - the order of the sorting. ASCENDING or DESCENDING
 * @param {SortBy} sortBy - what field the row should show
 */

interface SetSortingColumnAction extends Action<typeof SET_FILES_SORTING_COLUMN> { sortingColumn: SortBy, index: number }
export const setSortingColumn = (sortingColumn: SortBy, index: number): SetSortingColumnAction => ({
    type: SET_FILES_SORTING_COLUMN,
    sortingColumn,
    index
});

interface FileSelectorShownAction extends PayloadAction<typeof FILE_SELECTOR_SHOWN, { state: boolean }> { }
/**
 * Sets whether or not the file selector should be shown
 * @param {boolean} state whether or not the file selector is shown
 */
export const fileSelectorShown = (state: boolean): FileSelectorShownAction => ({
    type: FILE_SELECTOR_SHOWN,
    payload: { state }
});

type ReceiveFileSelectorFilesAction = PayloadAction<typeof RECEIVE_FILE_SELECTOR_FILES, { path: string, page: Page<File>, fileSelectorIsFavorites: boolean }>
/**
 * Returns action for receiving files for the fileselector.
 * @param {Page<File>} page the page of files
 * @param {string} path the path of the page the file selector is showing
 */
export const receiveFileSelectorFiles = (page: Page<File>, path: string, fileSelectorIsFavorites: boolean): ReceiveFileSelectorFilesAction => ({
    type: RECEIVE_FILE_SELECTOR_FILES,
    payload: {
        page,
        path: resolvePath(path),
        fileSelectorIsFavorites
    }
});

/**
 * Fetches a page that contains a specific path
 * @param {string} path The file path of the file that the page must contain.
 * @param {number} itemsPerPage The items per page within the page
 * @param {SortOrder} order the order to sort by, either ascending or descending
 * @param {SortBy} sortBy the field to be sorted by
 */
export async function fetchPageFromPath(path: string, itemsPerPage: number, order: SortOrder = SortOrder.ASCENDING, sortBy: SortBy = SortBy.PATH, attrs: FileResource[]): Promise<ReceivePage<typeof RECEIVE_FILES, File> | Error<typeof FILES_ERROR> | InvalidPathAction> {
    try {
        const { response } = await Cloud.get<Page<File>>(fileLookupQuery(path, itemsPerPage, order, sortBy, attrs))
        markFileAsChecked(path, response);
        return receiveFiles(response, getParentPath(resolvePath(path)), order, sortBy)
    } catch (e) {
        if (e.request.status === 404) return setInvalidPath("Not found");
        return setErrorMessage(`An error occured fetching the page for ${getFilenameFromPath(replaceHomeFolder(path, Cloud.homeFolder))}`)
    }
}

type InvalidPathAction = PayloadAction<typeof FILES_INVALID_PATH, { invalidPath: true, loading: false, error?: string, page: Page<File> }>
export const setInvalidPath = (error?: string): InvalidPathAction => ({
    type: FILES_INVALID_PATH,
    payload: { invalidPath: true, loading: false, error, page: emptyPage }
});

/**
 * 
 * @param path 
 * @param page 
 * @param itemsPerPage 
 */
export const fetchFileselectorFiles = async (path: string, page: number, itemsPerPage: number): Promise<ReceiveFileSelectorFilesAction | Error<typeof SET_FILE_SELECTOR_ERROR>> => {
    try {
        const { response } = await Cloud.get<Page<File>>(filepathQuery(path, page, itemsPerPage, SortOrder.ASCENDING, SortBy.FILE_TYPE));
        response.items.forEach(file => file.isChecked = false);
        return receiveFileSelectorFiles(response, resolvePath(path), false);
    } catch (e) {
        return setFileSelectorError({ error: `An error occured fetching the page for ${getFilenameFromPath(replaceHomeFolder(path, Cloud.homeFolder))}` });
    }
}
/**
 * Sets the fileselector as loading. Intended for use when retrieving files.
 */
export const setFileSelectorLoading = (): Action<typeof SET_FILE_SELECTOR_LOADING> => ({
    type: SET_FILE_SELECTOR_LOADING
});


type SetDisallowedPathsAction = PayloadAction<typeof SET_DISALLOWED_PATHS, { paths: string[] }>
/**
 * Sets paths for the file selector to omit.
 * @param {string[]} paths - the list of paths which shouldn't be displayed on
 * the fileselector modal.
 */
export const setDisallowedPaths = (paths: string[]): SetDisallowedPathsAction => ({
    type: SET_DISALLOWED_PATHS,
    payload: { paths }
});

type SetFileSelectorCallbackAction = PayloadAction<typeof SET_FILE_SELECTOR_CALLBACK, { callback: Function }>
/**
 * Callback to be executed on fileselection in FileSelector
 * @param callback - callback to be being executed
 */
export const setFileSelectorCallback = (callback: Function): SetFileSelectorCallbackAction => ({
    type: SET_FILE_SELECTOR_CALLBACK,
    payload: { callback }
});

/**
 * Sets the error message for use in the null means nothing will be rendered.
 * @param {string} error The error message to be set.
 */
export const setFileSelectorError = (error: { error?: string, statusCode?: number }): Error<typeof SET_FILE_SELECTOR_ERROR> => ({
    type: SET_FILE_SELECTOR_ERROR,
    payload: { ...error }
});

type CheckAllFilesAction = PayloadAction<typeof CHECK_ALL_FILES, { checked: boolean }>
export const checkAllFiles = (checked: boolean): CheckAllFilesAction => ({
    type: CHECK_ALL_FILES,
    payload: { checked }
});

type CheckFileAction = PayloadAction<typeof CHECK_FILE, { checked: boolean, path: string }>
export const checkFile = (checked: boolean, path: string): CheckFileAction => ({
    type: CHECK_FILE,
    payload: {
        checked,
        path
    }
});

type CreateFolderAction = Action<typeof CREATE_FOLDER>
export const createFolder = () => ({ type: CREATE_FOLDER });

export const fetchFileSelectorFavorites = async (pageNumber: number, itemsPerPage: number): Promise<ReceiveFileSelectorFilesAction | Error<typeof SET_FILE_SELECTOR_ERROR>> => {
    try {
        const result = await Cloud.get(favoritesQuery(pageNumber, itemsPerPage));
        return receiveFileSelectorFiles(result.response, "Favorites", true);
    } catch (e) {
        return setFileSelectorError({
            error: errorMessageOrDefault(e, "Error occurred fetching favorites"),
            statusCode: e.request.statusCode
        });
    }
}