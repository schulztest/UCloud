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
    SET_FILE_SELECTOR_ERROR
} from "./FilesReducer";
import { getFilenameFromPath, replaceHomeFolder, getParentPath } from "Utilities/FileUtilities";
import { Page, ReceivePage, SetLoadingAction, Error } from "Types";
import { SortOrder, SortBy, File } from "..";
import { Action } from "redux";
import { filepathQuery, fileLookupQuery } from "Utilities/FileUtilities";

/**
* Creates a promise to fetch files. Sorts the files based on sorting function passed,
* and implicitly sets {filesLoading} to false in the reducer when the files are fetched.
* @param {string} path is the path of the folder being queried
* @param {number} itemsPerPage number of items to be fetched
* @param {Page<File>} page number of the page to be fetched
*/

export const fetchFiles = (path: string, itemsPerPage: number, page: number, order: SortOrder, sortBy: SortBy): Promise<ReceivePage<typeof RECEIVE_FILES, File> | Error<typeof FILES_ERROR>> =>
    Cloud.get(filepathQuery(path, page, itemsPerPage, order, sortBy)).then(({ response }) =>
        receiveFiles(response, path, order, sortBy)
    ).catch(() =>
        setErrorMessage(`An error occurred fetching files for ${getFilenameFromPath(replaceHomeFolder(path, Cloud.homeFolder))}`)
    );

/**
 * Sets the error message for the Files component.
 * @param {error?} error the error message. undefined means nothing is rendered.
 */
export const setErrorMessage = (error?: string): Error<typeof FILES_ERROR> => ({
    type: FILES_ERROR,
    error
});

/**
* Updates the files stored. 
* Intended for use when sorting the files, checking or favoriting, for instance.
* @param {Page<File>} page contains the currently held page with modifications made to the files client-side
*/
export const updateFiles = (page: Page<File>): ReceivePage<typeof UPDATE_FILES, File> => ({
    type: UPDATE_FILES,
    page
});

/**
 * Sets whether or not the component is loading
 * @param {boolean} loading - whether or not it is loading
 */
export const setLoading = (loading: boolean): SetLoadingAction<typeof SET_FILES_LOADING> => ({
    type: SET_FILES_LOADING,
    loading
});

interface UpdatePathAction extends Action<typeof UPDATE_PATH> { path: string }
/**
 * Updates the path currently held intended for the files/fileinfo components.
 * @param {string} path - The current path for the component
 */
export const updatePath = (path: string): UpdatePathAction => ({
    type: UPDATE_PATH,
    path
});

interface ReceiveFiles extends ReceivePage<typeof RECEIVE_FILES, File> { path: string, sortOrder: SortOrder, sortBy: SortBy }
/**
 * The function used for the actual receiving the files, rather than the promise
 * @param {Page<File>} page - Contains the page
 * @param {string} path - The path the files were retrieved from
 * @param {SortOrder} sortOrder - The order in which the files were sorted
 * @param {SortBy} sortBy - the value the sorting was based on
 */
const receiveFiles = (page: Page<File>, path: string, sortOrder: SortOrder, sortBy: SortBy): ReceiveFiles => {
    // FIXME Checked and rename should be set in reducer
    page.items.forEach((f) => f.isChecked = f.beingRenamed = false);
    return {
        type: RECEIVE_FILES,
        page,
        path,
        sortOrder,
        sortBy
    }
};

export type SortingColumn = 0 | 1;
/**
 * Sets the column in the table that should be rendered (Not implemented)
 * @param index - the index of the sorting colum (0 or 1)
 * @param {SortOrder} asc - the order of the sorting. ASCENDING or DESCENDING
 * @param {SortBy} sortBy - what field the row should show
 */
export const setSortingColumn = (sortingColumn: SortBy, index: number) => ({
    type: SET_FILES_SORTING_COLUMN,
    sortingColumn,
    index
});

interface FileSelectorShownAction extends Action<typeof FILE_SELECTOR_SHOWN> { state: boolean }
/**
 * Sets whether or not the file selector should be shown
 * @param {boolean} state whether or not the file selector is shown
 */
export const fileSelectorShown = (state: boolean): FileSelectorShownAction => ({
    type: FILE_SELECTOR_SHOWN,
    state
});

interface ReceiveFileSelectorFilesAction extends ReceivePage<typeof RECEIVE_FILE_SELECTOR_FILES, File> { path: string }
/**
 * Returns action for receiving files for the fileselector.
 * @param {Page<File>} page the page of files
 * @param {string} path the path of the page the file selector is showing
 */
export const receiveFileSelectorFiles = (page: Page<File>, path: string): ReceiveFileSelectorFilesAction => ({
    type: RECEIVE_FILE_SELECTOR_FILES,
    page,
    path
});

/**
 * Fetches a page that contains a specific path
 * @param {string} path The file path that must be contained within the page.
 * @param {number} itemsPerPage The items per page within the page
 * @param {SortOrder} order the order to sort by, either ascending or descending
 * @param {SortBy} sortBy the field to be sorted by
 */
export const fetchPageFromPath = (path: string, itemsPerPage: number, order: SortOrder = SortOrder.ASCENDING, sortBy: SortBy = SortBy.PATH): Promise<ReceivePage<typeof RECEIVE_FILES, File> | Error<typeof FILES_ERROR>> =>
    Cloud.get(fileLookupQuery(path, itemsPerPage, order, sortBy))
        .then(({ response }) => receiveFiles(response, getParentPath(path), order, sortBy)).catch(() =>
            setErrorMessage(`An error occured fetching the page for ${getFilenameFromPath(replaceHomeFolder(path, Cloud.homeFolder))}`)
        ); // FIXME Add error handling

/**
 * 
 * @param path 
 * @param page 
 * @param itemsPerPage 
 */
export const fetchFileselectorFiles = (path: string, page: number, itemsPerPage: number): Promise<ReceiveFileSelectorFilesAction | Error<typeof SET_FILE_SELECTOR_ERROR>> =>
    Cloud.get(filepathQuery(path, page, itemsPerPage)).then(({ response }) => {
        response.items.forEach(file => file.isChecked = false);
        return receiveFileSelectorFiles(response, path);
    }).catch(() => setFileSelectorError(`An error occured fetching the page for ${getFilenameFromPath(replaceHomeFolder(path, Cloud.homeFolder))}`));

/**
 * Sets the fileselector as loading. Intended for when retrieving files.
 */
export const setFileSelectorLoading = (): Action => ({
    type: SET_FILE_SELECTOR_LOADING
});


interface SetDisallowedPathsAction extends Action { paths: string[] }
/**
 * Sets paths for the file selector to omit.
 * @param {string[]} paths - the list of paths which shouldn't be displayed on
 * the fileselector modal.
 */
export const setDisallowedPaths = (paths: string[]): SetDisallowedPathsAction => ({
    type: SET_DISALLOWED_PATHS,
    paths
});

interface SetFileSelectorCallbackAction extends Action { callback: Function }
/**
 * Callback to be executed on fileselection in FileSelector
 * @param callback - callback to be being executed
 */
export const setFileSelectorCallback = (callback: Function): SetFileSelectorCallbackAction => ({
    type: SET_FILE_SELECTOR_CALLBACK,
    callback
});

/**
 * Sets the error message for use in the null means nothing will be rendered.
 * @param {string} error The error message to be set.
 */
export const setFileSelectorError = (error?: string): Error<typeof SET_FILE_SELECTOR_ERROR> => ({
    type: SET_FILE_SELECTOR_ERROR,
    error
});

export const checkAllFiles = (checked: boolean, page: Page<File>) => {
    page.items.forEach((it) => it.isChecked = checked);
    return updateFiles(page);
}